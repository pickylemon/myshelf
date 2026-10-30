-- 사용자 도서 메타데이터 컬럼 보완
ALTER TABLE user_books
    ADD COLUMN publisher varchar(255),
    ADD COLUMN cover_image_url text;