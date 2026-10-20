package com.vocabler;

import java.security.Principal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning")
class LearningController {
    record NextRequest(@Positive long pairId, @NotNull TranslationDirection direction, @Positive Long packageId) {}
    record ReviewRequest(@NotBlank @Size(max=36) String ticket, @NotNull Boolean correct) {}
    private final LearningService learning;
    LearningController(LearningService learning) { this.learning = learning; }
    @GetMapping("/settings") LearningPolicy.Settings settings(Principal user) { return learning.settings(learning.userId(user.getName())); }
    @PutMapping("/settings") void saveSettings(Principal user, @Valid @RequestBody LearningPolicy.Settings settings) {
        learning.saveSettings(learning.userId(user.getName()), settings);
    }
    @PostMapping("/next") LearningService.Queue next(Principal user, @Valid @RequestBody NextRequest request) {
        return learning.next(learning.userId(user.getName()), request.pairId(), request.direction(), request.packageId());
    }
    @PostMapping("/review") LearningPolicy.Outcome review(Principal user, @Valid @RequestBody ReviewRequest request) {
        return learning.review(learning.userId(user.getName()), request.ticket(), request.correct());
    }
}
