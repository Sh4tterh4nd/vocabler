package com.vocabler;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Run with postgresTest against a dedicated, disposable database. */
class PostgresMigrationTest {
    @Test void singleAccountUpgradeAssignsTheExistingCatalog() { upgrade(false); }
    @Test void multipleAccountUpgradePreservesPrivateUnassignedVocabulary() { upgrade(true); }

    @Test void existingReviewHistoryBackfillsStreaksOnPostgres() {
        String url = System.getenv("TEST_DB_URL");
        assertNotNull(url);
        String user = System.getenv("TEST_DB_USER");
        String password = System.getenv("TEST_DB_PASSWORD");
        String schema = "streak_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("create schema " + schema);
        try {
            CorrectStreakMigrationTest.verifyUpgrade(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        } finally {
            admin.execute("drop schema " + schema + " cascade");
        }
    }

    private void upgrade(boolean multipleUsers) {
        String url = System.getenv("TEST_DB_URL");
        assertNotNull(url, "Run this test through the postgresTest Gradle task");
        assertTrue(url.startsWith("jdbc:postgresql:"));
        String user = System.getenv("TEST_DB_USER");
        String password = System.getenv("TEST_DB_PASSWORD");
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("create schema " + schema);
        String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var db = new JdbcTemplate(new DriverManagerDataSource(schemaUrl, user, password));
        try {
            Flyway.configure().dataSource(schemaUrl,user,password).defaultSchema(schema).target("1").load().migrate();
            db.update("insert into app_user(id,username,password_hash) values (100,'legacy','unused')");
            if (multipleUsers) db.update("insert into app_user(id,username,password_hash) values (101,'other','unused')");
            db.update("insert into language_pair(id,source_language,target_language) values (100,'German','Romanian')");
            db.update("insert into vocabulary_entry(id,pair_id,source_text,target_text) values (100,100,'Apfel','măr')");
            db.update("insert into vocabulary_package(id,pair_id,name) values (100,100,'Fruit')");
            db.update("insert into package_entry(package_id,entry_id) values (100,100)");
            Flyway.configure().dataSource(schemaUrl,user,password).defaultSchema(schema).load().migrate();
            if (multipleUsers) {
                assertNull(db.queryForObject("select owner_user_id from language_pair",Long.class));
                var access = new CatalogAccess(db);
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->access.requireRead(100,100));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->access.requireRead(101,100));
            } else assertEquals(100L,db.queryForObject("select owner_user_id from language_pair",Long.class));
            assertEquals("măr",db.queryForObject("select target_text from vocabulary_entry",String.class));
            assertEquals(1,db.queryForObject("select count(*) from package_entry",Integer.class));
            assertEquals(0,db.queryForObject("select count(*) from pair_share",Integer.class));
            db.update("insert into app_user(id,username,password_hash) values (102,'newowner','unused')");
            db.update("insert into language_pair(owner_user_id,source_language,target_language) values (102,'German','Romanian')");
            assertEquals(2,db.queryForObject("select count(*) from language_pair",Integer.class));
        } finally {
            // Only the uniquely named schema created by this test is removed.
            admin.execute("drop schema " + schema + " cascade");
        }
    }
}
