package com.vocabler;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LearningPolicyTest {
    private final Instant now = Instant.parse("2026-10-20T12:00:00Z");
    private final LearningPolicy.Settings settings = new LearningPolicy.Settings(75, 8, 1, 2, 0.2);

    @Test void decayUsesFractionalDaysAndStopsAtEight() {
        assertEquals(9.9, LearningPolicy.effectiveScore(10, now.minus(Duration.ofHours(12)), now, 0.2), 0.00001);
        assertEquals(8, LearningPolicy.effectiveScore(10, now.minus(Duration.ofDays(100)), now, 0.2));
        assertEquals(5, LearningPolicy.effectiveScore(5, now.minus(Duration.ofDays(100)), now, 0.2));
    }
    @Test void decayCanBeDisabledAndNeverRunsBackwards() {
        assertEquals(10, LearningPolicy.effectiveScore(10, now.minus(Duration.ofDays(100)), now, 0));
        assertEquals(9, LearningPolicy.effectiveScore(9, now.plusSeconds(60), now, 0.2));
    }
    @Test void rememberedMasteryReturnsDirectlyToTen() {
        var result = LearningPolicy.review(10, now.minus(Duration.ofDays(6)), true, 0, true, now, settings);
        assertEquals(8.8, result.before(), 0.00001);
        assertEquals(10, result.after());
        assertTrue(result.masteryRecall());
    }
    @Test void failedMasteryUsesNormalPenaltyAndRemovesShortcut() {
        var missed = LearningPolicy.review(10, now.minus(Duration.ofDays(6)), true, 0, false, now, settings);
        assertEquals(6.8, missed.after());
        assertFalse(missed.masteryRecall());
        var remembered = LearningPolicy.review(missed.after(), now, missed.masteryRecall(), missed.correctStreak(), true, now, settings);
        assertEquals(7.8, remembered.after());
    }
    @Test void scoresAreClampedAndMasteryMustBeEarned() {
        assertEquals(0, LearningPolicy.review(0.5, now, false, 0, false, now, settings).after());
        var result = LearningPolicy.review(9.5, now, false, 0, true, now, settings);
        assertEquals(10, result.after()); assertTrue(result.masteryRecall());
    }
    @Test void higherLearningScoresHaveLongerIntervals() {
        var low = LearningPolicy.review(1, now, false, 0, true, now, settings);
        var medium = LearningPolicy.review(3, now, false, 0, true, now, settings);
        var high = LearningPolicy.review(6, now, false, 0, true, now, settings);
        assertTrue(low.dueAt().isBefore(medium.dueAt()));
        assertTrue(medium.dueAt().isBefore(high.dueAt()));
        assertTrue(low.dueAt().isAfter(now));
        var graduated = LearningPolicy.review(8, now, false, 0, true, now, settings);
        var mastered = LearningPolicy.review(9, now, false, 0, true, now, settings);
        assertTrue(graduated.dueAt().isBefore(mastered.dueAt()));
    }
    @Test void fourConsecutiveCorrectReviewsMasterANewWord() {
        double score = 0;
        int streak = 0;
        Instant reviewAt = now;
        double[] expected = {1, 3, 6, 10};
        for (int index = 0; index < expected.length; index++) {
            var result = LearningPolicy.review(score, reviewAt, false, streak, true, reviewAt, settings);
            assertEquals(expected[index], result.after());
            assertEquals(index + 1, result.correctStreak());
            score = result.after();
            streak = result.correctStreak();
            reviewAt = result.dueAt();
        }
    }
    @Test void aMissResetsTheStreakAndTheNextGain() {
        var missed = LearningPolicy.review(6, now, false, 3, false, now, settings);
        assertEquals(4, missed.after());
        assertEquals(0, missed.correctStreak());
        var remembered = LearningPolicy.review(missed.after(), now, false, missed.correctStreak(), true, now, settings);
        assertEquals(5, remembered.after());
        assertEquals(1, remembered.correctStreak());
    }
    @Test void streakBonusUsesTheConfiguredGainAndIsCappedAtFourTimesBase() {
        var custom = new LearningPolicy.Settings(75, 8, 0.5, 2, 0.2);
        assertEquals(1.5, LearningPolicy.review(0, now, false, 2, true, now, custom).after());
        var capped = LearningPolicy.review(0, now, false, 100, true, now, custom);
        assertEquals(2, capped.after());
        assertEquals(101, capped.correctStreak());
        assertEquals(Integer.MAX_VALUE, LearningPolicy.review(0, now, false, Integer.MAX_VALUE, true, now, custom).correctStreak());
    }

}
