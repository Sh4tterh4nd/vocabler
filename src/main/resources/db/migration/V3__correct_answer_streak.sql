ALTER TABLE learning_progress
    ADD COLUMN correct_streak INTEGER NOT NULL DEFAULT 0 CHECK (correct_streak >= 0);

-- Support the history lookup when upgrading collections with many words and reviews.
CREATE INDEX review_history_word_direction ON review_history(user_id, entry_id, direction, id);

-- Preserve scores and schedules; recover each direction's current streak from its review history.
-- Review IDs order submissions even when multiple reviews have the same timestamp.
UPDATE learning_progress p
SET correct_streak = (
    SELECT COUNT(*) FROM review_history h
    WHERE h.user_id = p.user_id AND h.entry_id = p.entry_id AND h.direction = p.direction
      AND h.correct = TRUE
      AND h.id > COALESCE((
          SELECT MAX(m.id) FROM review_history m
          WHERE m.user_id = p.user_id AND m.entry_id = p.entry_id AND m.direction = p.direction
            AND m.correct = FALSE
      ), 0)
);
