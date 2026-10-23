package com.vocabler;

import java.time.*;
import jakarta.validation.constraints.*;

final class LearningPolicy {
    private LearningPolicy() {}
    record Settings(@Min(10) @Max(200) int activeLimit,
                    @DecimalMin("1") @DecimalMax("9.9") double graduationScore,
                    @DecimalMin("0.1") @DecimalMax("5") double correctGain,
                    @DecimalMin("0.1") @DecimalMax("10") double incorrectPenalty,
                    @DecimalMin("0") @DecimalMax("2") double dailyDecay) {}
    record Outcome(double before, double after, boolean masteryRecall, Instant dueAt, int correctStreak) {}

    static double effectiveScore(double score, Instant scoreAt, Instant now, double dailyDecay) {
        if (score <= 8) return score;
        double days = Math.max(0, Duration.between(scoreAt, now).toMillis() / 86_400_000.0);
        return Math.max(8, score - days * dailyDecay);
    }
    static Outcome review(double storedScore, Instant scoreAt, boolean masteryRecall, int correctStreak, boolean correct,
                          Instant now, Settings settings) {
        double before = effectiveScore(storedScore, scoreAt, now, settings.dailyDecay());
        int streak = correct ? (int) Math.min(Integer.MAX_VALUE, (long) correctStreak + 1) : 0;
        // Reward consecutive recall for this word and direction, capped at four times the base gain.
        double gain = settings.correctGain() * Math.min(streak, 4);
        double after = correct ? masteryRecall ? 10 : Math.min(10, before + gain)
            : Math.max(0, before - settings.incorrectPenalty());
        after = Math.round(after * 1000.0) / 1000.0;
        Duration interval = after < 3 ? Duration.ofMinutes(5) : after < 5 ? Duration.ofHours(8)
            : after < 8 ? Duration.ofDays(1) : after < 10 ? Duration.ofDays(3) : Duration.ofDays(7);
        return new Outcome(before, after, correct && after == 10, now.plus(interval), streak);
    }
}
