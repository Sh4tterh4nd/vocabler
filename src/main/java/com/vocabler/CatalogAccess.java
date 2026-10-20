package com.vocabler;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class CatalogAccess {
    private final JdbcTemplate db;
    CatalogAccess(JdbcTemplate db) { this.db = db; }

    long userId(String username) {
        return db.queryForObject("select id from app_user where username=?", Long.class, username);
    }
    long requireRead(long userId, long pairId) {
        List<Long> owners = db.queryForList("select owner_user_id from language_pair p where p.id=? and (p.owner_user_id=? or exists(select 1 from pair_share s where s.pair_id=p.id and s.user_id=?))", Long.class, pairId, userId, userId);
        if (owners.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Language pair not found");
        return owners.getFirst();
    }
    void requireReadForUpdate(long userId, long pairId) {
        // Pair-level locking orders review/enrollment and sharing revocation consistently.
        db.queryForList("select id from language_pair where id=? for update", Long.class, pairId);
        requireRead(userId, pairId);
    }
    void requireOwner(long userId, long pairId) {
        db.queryForList("select id from language_pair where id=? for update", Long.class, pairId);
        if (requireRead(userId, pairId) != userId)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can change or share this vocabulary");
    }
}
