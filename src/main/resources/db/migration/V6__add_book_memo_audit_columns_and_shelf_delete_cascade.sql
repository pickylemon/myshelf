ALTER TABLE user_books
    ADD COLUMN memo TEXT NULL,
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE shelf_books
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- 순서를 지정하지 않은 도서는 position을 null로 저장한다.
ALTER TABLE shelf_books
    ALTER COLUMN position DROP NOT NULL;

-- Shelf 삭제 시 연결만 제거하고 UserBook은 유지한다.
ALTER TABLE shelf_books
    DROP CONSTRAINT fk_shelf_books_shelf,
    ADD CONSTRAINT fk_shelf_books_shelf
        FOREIGN KEY (shelf_id)
            REFERENCES shelves (id)
            ON DELETE CASCADE;
