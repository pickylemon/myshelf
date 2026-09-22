create table users
(
    id       uuid primary key,
    email    varchar(255) not null unique,
    password varchar(255) not null
);

create table books
(
    id    uuid primary key,
    isbn  varchar(20) unique,
    title varchar(255) not null
);

create table user_books
(
    id      uuid primary key,
    user_id uuid not null,
    book_id uuid not null,

    constraint fk_user_books_user
        foreign key (user_id)
            references users (id),

    constraint fk_user_books_book
        foreign key (book_id)
            references books (id),

    constraint uk_user_books_user_book
        unique (user_id, book_id)
);