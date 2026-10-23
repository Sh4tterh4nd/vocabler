package com.vocabler;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
class LearningService {
    record Card(String ticket, long entryId, String prompt, String answer, String notes, double score, boolean maintenance, int correctStreak) {}
    record Queue(Card card, int activeCount, int activeLimit) {}
    record Progress(long entryId, double score, Instant scoreAt, Instant dueAt, boolean active, boolean masteryRecall, int correctStreak,
                    String source, String target, String notes) {}
    private final JdbcTemplate db;
    private final Clock clock;
    private final CatalogController catalog;
    private final CatalogAccess access;
    LearningService(JdbcTemplate db, Clock clock, CatalogController catalog, CatalogAccess access) { this.db = db; this.clock = clock; this.catalog = catalog; this.access = access; }

    long userId(String username) { return db.queryForObject("select id from app_user where username=?", Long.class, username); }
    LearningPolicy.Settings settings(long userId) {
        return db.queryForObject("select * from app_user where id=?", (rs, row) -> new LearningPolicy.Settings(
            rs.getInt("active_limit"), rs.getDouble("graduation_score"), rs.getDouble("correct_gain"),
            rs.getDouble("incorrect_penalty"), rs.getDouble("daily_decay")), userId);
    }
    @Transactional
    void saveSettings(long userId, LearningPolicy.Settings settings) {
        lock(userId);
        db.update("update app_user set active_limit=?, graduation_score=?, correct_gain=?, incorrect_penalty=?, daily_decay=? where id=?",
            settings.activeLimit(), settings.graduationScore(), settings.correctGain(), settings.incorrectPenalty(), settings.dailyDecay(), userId);
        // Existing scores retain the timestamp and are interpreted using the current decay setting.
    }
    private void lock(long userId) { db.queryForObject("select id from app_user where id=? for update", Long.class, userId); }
    private OffsetDateTime timestamp(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }

    @Transactional
    Queue next(long userId, long pairId, TranslationDirection direction, Long packageId) {
        lock(userId);
        access.requireReadForUpdate(userId, pairId);
        if (packageId != null) catalog.requirePackage(pairId, packageId);
        LearningPolicy.Settings settings = settings(userId);
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        // Release graduated entries (also after lowering the graduation setting).
        db.update("update learning_progress set active=false where user_id=? and direction=? and score>=? and entry_id in (select id from vocabulary_entry where pair_id=?)",
            userId, direction.name(), settings.graduationScore(), pairId);
        if (packageId != null) db.update("update learning_progress set active=false where user_id=? and direction=? and entry_id in (select id from vocabulary_entry where pair_id=?) and entry_id not in (select entry_id from package_entry where package_id=?)", userId, direction.name(), pairId, packageId);
        List<Long> active = db.queryForList("select p.entry_id from learning_progress p join vocabulary_entry e on e.id=p.entry_id where p.user_id=? and p.direction=? and p.active=true and e.pair_id=? order by p.entry_id", Long.class, userId, direction.name(), pairId);
        // If the list size was reduced, park excess entries; they remain available for later enrollment.
        for (int index = settings.activeLimit(); index < active.size(); index++)
            db.update("update learning_progress set active=false where user_id=? and entry_id=? and direction=?", userId, active.get(index), direction.name());
        int activeCount = Math.min(active.size(), settings.activeLimit());
        String packageClause = packageId == null ? "" : " and exists (select 1 from package_entry pe where pe.entry_id=e.id and pe.package_id=" + packageId + ")";
        int slots = settings.activeLimit() - activeCount;
        if (slots > 0) {
            List<Long> candidates = db.queryForList("select e.id from vocabulary_entry e left join learning_progress p on p.entry_id=e.id and p.user_id=? and p.direction=? where e.pair_id=? and (p.entry_id is null or (p.active=false and p.score<?))" + packageClause + " order by e.id limit ?", Long.class,
                userId, direction.name(), pairId, settings.graduationScore(), slots);
            for (long entryId : candidates) {
                int updated = db.update("update learning_progress set active=true where user_id=? and entry_id=? and direction=?", userId, entryId, direction.name());
                if (updated == 0) db.update("insert into learning_progress(user_id,entry_id,direction,score_at,due_at) values (?,?,?,?,?)",
                    userId, entryId, direction.name(), timestamp(now), timestamp(now));
            }
            activeCount += candidates.size();
        }
        List<Progress> due = db.query("select p.*, e.source_text, e.target_text, e.notes from learning_progress p join vocabulary_entry e on e.id=p.entry_id where p.user_id=? and p.direction=? and e.pair_id=? and p.due_at<=?" + packageClause + " order by p.due_at, p.score, p.entry_id", (rs, row) -> new Progress(
            rs.getLong("entry_id"), rs.getDouble("score"), rs.getObject("score_at", OffsetDateTime.class).toInstant(),
            rs.getObject("due_at", OffsetDateTime.class).toInstant(), rs.getBoolean("active"), rs.getBoolean("mastery_recall"), rs.getInt("correct_streak"),
            rs.getString("source_text"), rs.getString("target_text"), rs.getString("notes")), userId, direction.name(), pairId, timestamp(now));
        due = due.stream().filter(p -> LearningPolicy.effectiveScore(p.score(), p.scoreAt(), now, settings.dailyDecay()) < 10).toList();
        long reviews = db.queryForObject("select count(*) from review_history h join vocabulary_entry e on e.id=h.entry_id where h.user_id=? and h.direction=? and e.pair_id=?", Long.class, userId, direction.name(), pairId);
        boolean maintenanceTurn = reviews % 5 == 4;
        Progress selected = due.stream().filter(p -> p.active() != maintenanceTurn).findFirst().orElse(null);
        if (selected == null && !due.isEmpty()) selected = due.getFirst();
        if (selected == null) return new Queue(null, activeCount, settings.activeLimit());
        // A single outstanding ticket per user/entry/direction prevents double submissions.
        db.update("delete from review_ticket where user_id=? and created_at<?", userId, timestamp(now.minus(Duration.ofHours(1))));
        List<String> tickets = db.queryForList("select id from review_ticket where user_id=? and entry_id=? and direction=?", String.class, userId, selected.entryId(), direction.name());
        String ticket = tickets.isEmpty() ? UUID.randomUUID().toString() : tickets.getFirst();
        if (tickets.isEmpty()) db.update("insert into review_ticket(id,user_id,entry_id,direction,created_at) values (?,?,?,?,?)", ticket, userId, selected.entryId(), direction.name(), timestamp(now));
        boolean forward = direction == TranslationDirection.FORWARD;
        return new Queue(new Card(ticket, selected.entryId(), forward ? selected.source() : selected.target(),
            forward ? selected.target() : selected.source(), selected.notes(),
            LearningPolicy.effectiveScore(selected.score(), selected.scoreAt(), now, settings.dailyDecay()), !selected.active(), selected.correctStreak()), activeCount, settings.activeLimit());
    }

    @Transactional
    LearningPolicy.Outcome review(long userId, String ticket, boolean correct) {
        lock(userId);
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var tickets = db.queryForList("select * from review_ticket where id=? and user_id=? and created_at>=?", ticket, userId, timestamp(now.minus(Duration.ofHours(1))));
        if (tickets.isEmpty()) throw new ResponseStatusException(CONFLICT, "This review expired or was already answered. Load another card.");
        var issued = tickets.getFirst();
        long entryId = ((Number) issued.get("ENTRY_ID")).longValue();
        String direction = (String) issued.get("DIRECTION");
        var pairs = db.queryForList("select pair_id from vocabulary_entry where id=?", Long.class, entryId);
        if (pairs.isEmpty()) throw new ResponseStatusException(CONFLICT, "This word was deleted. Load another card.");
        long pairId = pairs.getFirst();
        access.requireReadForUpdate(userId, pairId);
        // A deletion may have cascaded to the ticket while this review waited for the pair lock.
        if (db.queryForObject("select count(*) from review_ticket where id=? and user_id=?", Integer.class, ticket, userId) == 0)
            throw new ResponseStatusException(CONFLICT, "This review is no longer available. Load another card.");
        Progress progress = db.queryForObject("select * from learning_progress where user_id=? and entry_id=? and direction=?", (rs, row) -> new Progress(
            entryId, rs.getDouble("score"), rs.getObject("score_at", OffsetDateTime.class).toInstant(),
            rs.getObject("due_at", OffsetDateTime.class).toInstant(), rs.getBoolean("active"), rs.getBoolean("mastery_recall"), rs.getInt("correct_streak"), "", "", ""), userId, entryId, direction);
        LearningPolicy.Settings settings = settings(userId);
        LearningPolicy.Outcome outcome = LearningPolicy.review(progress.score(), progress.scoreAt(), progress.masteryRecall(), progress.correctStreak(), correct, now, settings);
        boolean active = progress.active() && outcome.after() < settings.graduationScore();
        // Failed maintenance returns to the active list when a slot is free; otherwise it stays due for maintenance.
        if (!active && outcome.after() < settings.graduationScore()) {
            int count = db.queryForObject("select count(*) from learning_progress p join vocabulary_entry e on e.id=p.entry_id where p.user_id=? and p.direction=? and p.active=true and e.pair_id=(select pair_id from vocabulary_entry where id=?)", Integer.class, userId, direction, entryId);
            active = count < settings.activeLimit();
        }
        db.update("update learning_progress set score=?,score_at=?,due_at=?,active=?,mastery_recall=?,correct_streak=? where user_id=? and entry_id=? and direction=?",
            outcome.after(), timestamp(now), timestamp(outcome.dueAt()), active, outcome.masteryRecall(), outcome.correctStreak(), userId, entryId, direction);
        db.update("insert into review_history(user_id,entry_id,direction,correct,score_before,score_after,reviewed_at) values (?,?,?,?,?,?,?)",
            userId, entryId, direction, correct, outcome.before(), outcome.after(), timestamp(now));
        db.update("delete from review_ticket where id=?", ticket);
        return outcome;
    }
}
