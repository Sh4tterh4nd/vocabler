package com.vocabler;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/pairs")
class CatalogController {
    record PairInput(@NotBlank @Size(max=60) String sourceLanguage, @NotBlank @Size(max=60) String targetLanguage) {}
    record EntryInput(@NotBlank @Size(max=200) String sourceText, @NotBlank @Size(max=200) String targetText, @Size(max=1000) String notes) {}
    record EntryIdsInput(@NotEmpty @Size(max=5000) List<@NotNull @Positive Long> entryIds) {}
    record StoredEntry(long id, String source, String target) {}
    record EntryKey(String source, String target) {}
    record ProgressKey(long userId, long entryId, String direction) {}
    record StoredProgress(long userId, long entryId, String direction, double score, OffsetDateTime scoreAt,
                          OffsetDateTime dueAt, boolean active, boolean masteryRecall) {}
    record PackageInput(@NotBlank @Size(max=100) String name) {}
    private final JdbcTemplate db;
    private final CatalogAccess access;
    CatalogController(JdbcTemplate db, CatalogAccess access) { this.db = db; this.access = access; }

    @GetMapping List<Map<String,Object>> pairs(Principal user) {
        long userId = access.userId(user.getName());
        return db.queryForList("select p.id, p.source_language, p.target_language, u.username as owner_username, (p.owner_user_id=?) as is_owner, (select count(*) from vocabulary_entry e where e.pair_id=p.id) as entry_count from language_pair p join app_user u on u.id=p.owner_user_id where p.owner_user_id=? or exists(select 1 from pair_share s where s.pair_id=p.id and s.user_id=?) order by p.id", userId, userId, userId);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    void createPair(Principal user, @Valid @RequestBody PairInput input) {
        if (input.sourceLanguage().strip().equalsIgnoreCase(input.targetLanguage().strip()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose two different languages");
        db.update("insert into language_pair(owner_user_id,source_language,target_language) values (?, ?, ?)", access.userId(user.getName()), input.sourceLanguage().strip(), input.targetLanguage().strip());
    }
    void requirePackage(long pairId, long packageId) {
        if (db.queryForObject("select count(*) from vocabulary_package where id=? and pair_id=?", Integer.class, packageId, pairId) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found in this language pair");
    }
    @GetMapping("/{pairId}/entries") List<Map<String,Object>> entries(Principal user, @PathVariable long pairId, @RequestParam(required=false) Long packageId) {
        access.requireRead(access.userId(user.getName()), pairId);
        if (packageId != null) requirePackage(pairId, packageId);
        return packageId == null ? db.queryForList("select * from vocabulary_entry where pair_id=? order by id", pairId)
            : db.queryForList("select e.* from vocabulary_entry e join package_entry pe on pe.entry_id=e.id where e.pair_id=? and pe.package_id=? order by e.id", pairId, packageId);
    }
    @PostMapping("/{pairId}/entries") @ResponseStatus(HttpStatus.CREATED) @Transactional
    void createEntry(Principal user, @PathVariable long pairId, @Valid @RequestBody EntryInput input) {
        access.requireOwner(access.userId(user.getName()), pairId);
        db.update("insert into vocabulary_entry(pair_id, source_text, target_text, notes) values (?, ?, ?, ?)", pairId, input.sourceText().strip(), input.targetText().strip(), input.notes() == null ? "" : input.notes().strip());
    }
    @DeleteMapping("/{pairId}/entries/{entryId}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    void deleteEntry(Principal user, @PathVariable long pairId, @PathVariable long entryId) {
        deleteEntries(user, pairId, new EntryIdsInput(List.of(entryId)));
    }
    @DeleteMapping("/{pairId}/entries") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    void deleteEntries(Principal user, @PathVariable long pairId, @Valid @RequestBody EntryIdsInput input) {
        access.requireOwner(access.userId(user.getName()), pairId);
        var ids = new LinkedHashSet<>(input.entryIds());
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        var parameters = new ArrayList<Object>();
        parameters.add(pairId);
        parameters.addAll(ids);
        String selection = "pair_id=? and id in (" + placeholders + ")";
        int found = db.queryForObject("select count(*) from vocabulary_entry where " + selection, Integer.class, parameters.toArray());
        if (found != ids.size())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more words were not found in this language pair; nothing was deleted");
        // Foreign keys cascade to package memberships and every user's progress, tickets and history.
        db.update("delete from vocabulary_entry where " + selection, parameters.toArray());
    }
    @PostMapping("/{pairId}/entries/swap") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    void swapEntries(Principal user, @PathVariable long pairId, @Valid @RequestBody EntryIdsInput input) {
        access.requireOwner(access.userId(user.getName()), pairId);
        var ids = new LinkedHashSet<>(input.entryIds());
        var entries = db.query("select id, source_text, target_text from vocabulary_entry where pair_id=?",
            (rs, row) -> new StoredEntry(rs.getLong(1), rs.getString(2), rs.getString(3)), pairId);
        var selected = entries.stream().filter(entry -> ids.contains(entry.id())).toList();
        if (selected.size() != ids.size())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more words were not found in this language pair; nothing was swapped");
        var existing = new HashMap<EntryKey, Long>();
        for (var entry : entries) existing.put(new EntryKey(entry.source(), entry.target()), entry.id());
        for (var entry : selected) {
            Long conflict = existing.get(new EntryKey(entry.target(), entry.source()));
            if (conflict != null && !ids.contains(conflict))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Swapping would duplicate another word in this language pair; nothing was changed");
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] parameters = ids.toArray();
        var progress = db.query("select * from learning_progress where entry_id in (" + placeholders + ")",
            (rs, row) -> new StoredProgress(rs.getLong("user_id"), rs.getLong("entry_id"), rs.getString("direction"),
                rs.getDouble("score"), rs.getObject("score_at", OffsetDateTime.class), rs.getObject("due_at", OffsetDateTime.class),
                rs.getBoolean("active"), rs.getBoolean("mastery_recall")), parameters);
        var progressKeys = new HashSet<ProgressKey>();
        for (var item : progress) progressKeys.add(new ProgressKey(item.userId(), item.entryId(), item.direction()));
        // Reserve unique temporary values so a selected A/B and B/A can exchange places safely.
        String temporary = "__swap_" + UUID.randomUUID() + "_";
        for (var entry : selected)
            db.update("update vocabulary_entry set source_text=? where id=?", temporary + entry.id(), entry.id());
        for (var entry : selected)
            db.update("update vocabulary_entry set source_text=?, target_text=? where id=?", entry.target(), entry.source(), entry.id());
        // Keep scores and scheduling with the actual question/answer, for every user.
        // Update data when both directions exist; changing both primary keys directly would collide.
        for (var item : progress) {
            String opposite = item.direction().equals("FORWARD") ? "REVERSE" : "FORWARD";
            if (progressKeys.contains(new ProgressKey(item.userId(), item.entryId(), opposite)))
                db.update("update learning_progress set score=?, score_at=?, due_at=?, active=?, mastery_recall=? where user_id=? and entry_id=? and direction=?",
                    item.score(), item.scoreAt(), item.dueAt(), item.active(), item.masteryRecall(), item.userId(), item.entryId(), opposite);
            else
                db.update("update learning_progress set direction=? where user_id=? and entry_id=? and direction=?",
                    opposite, item.userId(), item.entryId(), item.direction());
        }
        db.update("update review_history set direction=case when direction='FORWARD' then 'REVERSE' else 'FORWARD' end where entry_id in (" + placeholders + ")", parameters);
        db.update("delete from review_ticket where entry_id in (" + placeholders + ")", parameters);
    }
    @GetMapping("/{pairId}/packages") List<Map<String,Object>> packages(Principal user, @PathVariable long pairId) {
        access.requireRead(access.userId(user.getName()), pairId);
        return db.queryForList("select p.id, p.name, (select count(*) from package_entry pe where pe.package_id=p.id) as entry_count from vocabulary_package p where p.pair_id=? order by p.id", pairId);
    }
    @PostMapping("/{pairId}/packages") @ResponseStatus(HttpStatus.CREATED) @Transactional
    void createPackage(Principal user, @PathVariable long pairId, @Valid @RequestBody PackageInput input) {
        access.requireOwner(access.userId(user.getName()), pairId);
        db.update("insert into vocabulary_package(pair_id, name) values (?, ?)", pairId, input.name().strip());
    }
    @PutMapping("/{pairId}/packages/{packageId}/entries/{entryId}") @Transactional
    void addToPackage(Principal user, @PathVariable long pairId, @PathVariable long packageId, @PathVariable long entryId) {
        access.requireOwner(access.userId(user.getName()), pairId);
        requirePackage(pairId, packageId);
        requireEntry(pairId, entryId);
        db.update("insert into package_entry(package_id, entry_id) select ?, ? where not exists (select 1 from package_entry where package_id=? and entry_id=?)", packageId, entryId, packageId, entryId);
    }
    @DeleteMapping("/{pairId}/packages/{packageId}/entries/{entryId}") @Transactional
    void removeFromPackage(Principal user, @PathVariable long pairId, @PathVariable long packageId, @PathVariable long entryId) {
        access.requireOwner(access.userId(user.getName()), pairId);
        requirePackage(pairId, packageId);
        requireEntry(pairId, entryId);
        db.update("delete from package_entry where package_id=? and entry_id=?", packageId, entryId);
    }
    private void requireEntry(long pairId, long entryId) {
        if (db.queryForObject("select count(*) from vocabulary_entry where id=? and pair_id=?", Integer.class, entryId, pairId) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found in this language pair");
    }
}
