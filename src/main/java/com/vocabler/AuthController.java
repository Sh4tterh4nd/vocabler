package com.vocabler;

import java.security.Principal;
import java.util.Locale;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    record Registration(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,40}") String username,
                        @NotNull @Size(min=10, max=72) String password) {}
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    AuthController(JdbcTemplate db, PasswordEncoder encoder) { this.db = db; this.encoder = encoder; }

    @GetMapping("/csrf") Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    void register(@Valid @RequestBody Registration registration) {
        if (registration.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must fit within 72 UTF-8 bytes");
        try {
            db.update("insert into app_user(username, password_hash) values (?, ?)",
                registration.username().toLowerCase(Locale.ROOT), encoder.encode(registration.password()));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already taken");
        }
    }
    @GetMapping("/me") Map<String, String> me(Principal user) { return Map.of("username", user.getName()); }
}
