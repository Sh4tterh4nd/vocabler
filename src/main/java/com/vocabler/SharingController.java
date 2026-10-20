package com.vocabler;

import java.security.Principal;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/pairs/{pairId}/shares")
class SharingController {
    record ShareInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,40}") String username) {}
    private final JdbcTemplate db;
    private final CatalogAccess access;
    SharingController(JdbcTemplate db, CatalogAccess access) { this.db = db; this.access = access; }

    @GetMapping @Transactional
    List<Map<String,Object>> shares(Principal user, @PathVariable long pairId) {
        access.requireOwner(access.userId(user.getName()), pairId);
        return db.queryForList("select u.id,u.username from pair_share s join app_user u on u.id=s.user_id where s.pair_id=? order by u.username", pairId);
    }
    @PutMapping @Transactional
    void share(Principal user, @PathVariable long pairId, @Valid @RequestBody ShareInput input) {
        long ownerId = access.userId(user.getName());
        access.requireOwner(ownerId, pairId);
        var recipients = db.queryForList("select id from app_user where username=?", Long.class, input.username().toLowerCase(Locale.ROOT));
        if (recipients.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "No account has that username");
        long recipient = recipients.getFirst();
        if (recipient == ownerId) throw new ResponseStatusException(BAD_REQUEST, "You already own this language pair");
        db.update("insert into pair_share(pair_id,user_id) select ?,? where not exists(select 1 from pair_share where pair_id=? and user_id=?)", pairId, recipient, pairId, recipient);
    }
    @DeleteMapping("/{recipientId}") @Transactional
    void revoke(Principal user, @PathVariable long pairId, @PathVariable long recipientId) {
        access.requireOwner(access.userId(user.getName()), pairId);
        db.update("delete from pair_share where pair_id=? and user_id=?", pairId, recipientId);
        db.update("delete from review_ticket where user_id=? and entry_id in(select id from vocabulary_entry where pair_id=?)", recipientId, pairId);
        // Personal learning progress is preserved if the owner shares the pair again.
    }
}
