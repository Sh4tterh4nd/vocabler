package com.vocabler;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;

class CorrectStreakMigrationTest {
    @Test void existingHistoryBackfillsIndependentStreaksWithoutChangingScores() {
        verifyUpgrade("jdbc:h2:mem:streak-upgrade-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    static void verifyUpgrade(String url, String user, String password) {
        // Keep the migration connection open: H2 check constraints retain their creation session.
        var source = new SingleConnectionDataSource(url, user, password, true);
        try {
            Flyway.configure().dataSource(source).target("2").load().migrate();
            var db = new JdbcTemplate(source);
            db.update("insert into app_user(id,username,password_hash) values (100,'alice','unused'),(101,'bob','unused')");
            db.update("insert into language_pair(id,owner_user_id,source_language,target_language) values (100,100,'German','Romanian')");
            db.update("insert into vocabulary_entry(id,pair_id,source_text,target_text) values (100,100,'Apfel','măr'),(101,100,'Brot','pâine')");
            var at = OffsetDateTime.parse("2026-10-20T12:00:00Z");
            Object[][] progress = {{100L,100L,"FORWARD",2}, {100L,100L,"REVERSE",1}, {101L,100L,"FORWARD",3},
                {100L,101L,"FORWARD",0}, {100L,101L,"REVERSE",0}, {101L,101L,"REVERSE",0}};
            for (var row : progress)
                db.update("insert into learning_progress(user_id,entry_id,direction,score,score_at,due_at) values (?,?,?,6,?,?)", row[0],row[1],row[2],at,at.plusDays(1));
            Object[][] history = {{100L,100L,"FORWARD",true}, {101L,100L,"FORWARD",true}, {100L,100L,"FORWARD",true},
                {100L,100L,"FORWARD",false}, {100L,100L,"REVERSE",true}, {100L,100L,"FORWARD",true},
                {101L,100L,"FORWARD",true}, {100L,101L,"FORWARD",false}, {100L,100L,"FORWARD",true},
                {101L,100L,"FORWARD",true}, {101L,101L,"REVERSE",true}, {101L,101L,"REVERSE",false}};
            for (var row : history)
                db.update("insert into review_history(user_id,entry_id,direction,correct,score_before,score_after,reviewed_at) values (?,?,?,?,0,1,?)", row[0],row[1],row[2],row[3],at);
            var before = db.queryForList("select * from learning_progress order by user_id,entry_id,direction");
            Flyway.configure().dataSource(source).load().migrate();
            for (var row : progress)
                assertEquals(row[3], db.queryForObject("select correct_streak from learning_progress where user_id=? and entry_id=? and direction=?", Integer.class,row[0],row[1],row[2]));
            var after = db.queryForList("select * from learning_progress order by user_id,entry_id,direction").stream()
                .map(row -> { var copy = new HashMap<>(row); copy.keySet().removeIf(key -> key.equalsIgnoreCase("correct_streak")); return copy; }).toList();
            assertEquals(before, after);
            db.update("insert into learning_progress(user_id,entry_id,direction,score_at,due_at) values (101,101,'FORWARD',?,?)", at,at);
            assertEquals(0, db.queryForObject("select correct_streak from learning_progress where user_id=101 and entry_id=101 and direction='FORWARD'", Integer.class));
        } finally {
            source.destroy();
        }
    }
}
