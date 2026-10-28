
-- 1. Users

create table users
(
    id       uuid primary key,
    email    varchar(255) not null,
    password varchar(255) not null,

    constraint uk_users_email unique (email)
);


-- 2. Reading Statuses (Lookup Table)

create table reading_statuses
(
    code varchar(30) primary key,
    name varchar(50) not null
);

insert into reading_statuses (code, name)
values ('WANT_TO_READ', '읽고 싶음'),
       ('READING', '읽는 중'),
       ('READ', '완독');


-- 3. Shelves

create table shelves
(
    id          uuid primary key,
    user_id     uuid not null,
    name        varchar(255) not null,
    description text,

    constraint fk_shelves_user
        foreign key (user_id)
            references users (id)
);

create index idx_shelves_user_id
    on shelves (user_id);


-- 4. User Books

create table user_books
(
    id                  uuid primary key,
    user_id             uuid not null,
    isbn                varchar(20) not null,
    title               varchar(255) not null,
    author              varchar(255),
    reading_status_code varchar(30) not null,

    constraint fk_user_books_user
        foreign key (user_id)
            references users (id),

    constraint fk_user_books_reading_status
        foreign key (reading_status_code)
            references reading_statuses (code),

    constraint uk_user_books_user_isbn
        unique (user_id, isbn)
);


-- 5. Shelf Books

create table shelf_books
(
    id           uuid primary key,
    shelf_id     uuid not null,
    user_book_id uuid not null,
    position     integer not null,

    constraint fk_shelf_books_shelf
        foreign key (shelf_id)
            references shelves (id),

    constraint fk_shelf_books_user_book
        foreign key (user_book_id)
            references user_books (id),

    constraint uk_shelf_books_shelf_user_book
        unique (shelf_id, user_book_id)
);

create index idx_shelf_books_user_book_id
    on shelf_books (user_book_id);
