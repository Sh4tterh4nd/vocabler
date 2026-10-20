package com.vocabler;

import java.security.Principal;
import java.util.List;
import java.util.Map;
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
