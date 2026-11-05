ALTER TABLE shelf_books
    ADD COLUMN memo TEXT,
    ADD CONSTRAINT ck_shelf_books_memo_length
        CHECK (char_length(memo) <= 2000);

ALTER TABLE user_books
    DROP COLUMN memo;
