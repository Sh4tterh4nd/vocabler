package com.vocabler;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipMigrationTest {
    private String database() { return "jdbc:h2:mem:upgrade-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"; }
    private JdbcTemplate legacy(String url, boolean multipleUsers) {
        Flyway.configure().dataSource(url,"sa","").target("1").load().migrate();
        var db = new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        db.update("insert into app_user(id,username,password_hash) values (100,'legacy','unused')");
        if (multipleUsers) db.update("insert into app_user(id,username,password_hash) values (101,'other','unused')");
        db.update("insert into language_pair(id,source_language,target_language) values (100,'German','Romanian')");
        db.update("insert into vocabulary_entry(id,pair_id,source_text,target_text) values (100,100,'Apfel','măr')");
        return db;
    }
    @Test void oneAccountReceivesItsExistingCatalog() {
        String url = database(); var db = legacy(url,false);
        Flyway.configure().dataSource(url,"sa","").load().migrate();
        assertEquals(100L,db.queryForObject("select owner_user_id from language_pair",Long.class));
        assertEquals("măr",db.queryForObject("select target_text from vocabulary_entry",String.class));
        assertEquals(0,db.queryForObject("select count(*) from pair_share",Integer.class));
    }
    @Test void ambiguousLegacyOwnershipIsPreservedWithoutAutomaticExposure() {
        String url = database(); var db = legacy(url,true);
        Flyway.configure().dataSource(url,"sa","").load().migrate();
        assertNull(db.queryForObject("select owner_user_id from language_pair",Long.class));
        assertEquals(1,db.queryForObject("select count(*) from vocabulary_entry",Integer.class));
        var access = new CatalogAccess(db);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->access.requireRead(100,100));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->access.requireRead(101,100));
        db.update("insert into language_pair(owner_user_id,source_language,target_language) values (100,'German','Romanian')");
        db.update("insert into language_pair(owner_user_id,source_language,target_language) values (101,'German','Romanian')");
        assertEquals(3,db.queryForObject("select count(*) from language_pair",Integer.class));
    }
}
