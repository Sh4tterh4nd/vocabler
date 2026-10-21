package com.vocabler;

import java.io.*;
import java.security.Principal;
import java.nio.charset.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/pairs/{pairId}/import")
class CsvImportController {
    record Key(String source, String target) {}
    record Row(long row, String source, String target, String notes, String error, boolean duplicate) {}
    record Result(List<Row> rows, int newEntries, int duplicates, int errors, boolean committed) {}
    private final JdbcTemplate db;
    private final CatalogController catalog;
    private final CatalogAccess access;
    CsvImportController(JdbcTemplate db, CatalogController catalog, CatalogAccess access) { this.db = db; this.catalog = catalog; this.access = access; }

    @PostMapping @Transactional
    Result upload(Principal user, @PathVariable long pairId, @RequestParam MultipartFile file,
                  @RequestParam(defaultValue="false") boolean commit, @RequestParam(required=false) Long packageId,
                  @RequestParam(defaultValue="FORWARD") TranslationDirection direction) {
        access.requireOwner(access.userId(user.getName()), pairId);
        // Serialize imports within a pair, including the duplicate check and commit.
        db.queryForObject("select id from language_pair where id=? for update", Long.class, pairId);
        if (packageId != null) catalog.requirePackage(pairId, packageId);
        Set<Key> seen = new HashSet<>(db.query("select source_text, target_text from vocabulary_entry where pair_id=?",
            (rs, index) -> new Key(rs.getString(1), rs.getString(2)), pairId));
        List<Row> rows = new ArrayList<>();
        try {
            String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(file.getBytes())).toString();
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).setTrim(true).get();
            try (CSVParser parser = format.parse(new StringReader(content))) {
                Map<String,Integer> headers = parser.getHeaderMap();
                if (!headers.containsKey("source") || !headers.containsKey("target") ||
                    headers.keySet().stream().anyMatch(h -> !Set.of("source", "target", "notes").contains(h)))
                    throw new ResponseStatusException(BAD_REQUEST, "Use CSV headers source,target (two columns) or source,target,notes (three columns). Each row must match the header column count.");
                for (CSVRecord record : parser) {
                    if (rows.size() >= 5000) throw new ResponseStatusException(BAD_REQUEST, "Import at most 5000 rows at a time");
                    String csvSource = record.isSet("source") ? record.get("source").strip() : "";
                    String csvTarget = record.isSet("target") ? record.get("target").strip() : "";
                    // Normalize to the pair's stored order before duplicate detection, preview or commit.
                    String source = direction == TranslationDirection.FORWARD ? csvSource : csvTarget;
                    String target = direction == TranslationDirection.FORWARD ? csvTarget : csvSource;
                    String notes = record.isSet("notes") ? record.get("notes").strip() : "";
                    String error = !record.isConsistent() ? "Column count does not match the header" :
                        source.isBlank() || target.isBlank() ? "Both translations are required" :
                        source.length() > 200 || target.length() > 200 ? "Translations must have at most 200 characters" :
                        notes.length() > 1000 ? "Notes must have at most 1000 characters" : null;
                    boolean duplicate = error == null && !seen.add(new Key(source, target));
                    rows.add(new Row(record.getRecordNumber() + 1, source, target, notes, error, duplicate));
                }
            }
        } catch (IOException | IllegalArgumentException | UncheckedIOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid CSV. Use UTF-8 encoding, commas, and quoted fields where needed.");
        }
        if (rows.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "CSV has no vocabulary rows");
        int errors = (int) rows.stream().filter(row -> row.error() != null).count();
        int duplicates = (int) rows.stream().filter(Row::duplicate).count();
        int newEntries = rows.size() - errors - duplicates;
        if (commit && errors > 0) throw new ResponseStatusException(BAD_REQUEST, "Fix all invalid rows before importing; nothing was saved");
        Set<Key> assignedToPackage = new HashSet<>();
        if (commit) for (Row row : rows) {
            if (!row.duplicate()) db.update("insert into vocabulary_entry(pair_id, source_text, target_text, notes) values (?, ?, ?, ?)",
                pairId, row.source(), row.target(), row.notes());
            if (packageId != null && assignedToPackage.add(new Key(row.source(), row.target()))) {
                Long entryId = db.queryForObject("select id from vocabulary_entry where pair_id=? and source_text=? and target_text=?", Long.class, pairId, row.source(), row.target());
                db.update("insert into package_entry(package_id, entry_id) select ?, ? where not exists (select 1 from package_entry where package_id=? and entry_id=?)", packageId, entryId, packageId, entryId);
            }
        }
        return new Result(rows, newEntries, duplicates, errors, commit);
    }
}
