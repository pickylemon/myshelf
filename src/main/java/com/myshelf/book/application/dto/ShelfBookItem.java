package com.myshelf.book.application.dto;

import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.domain.ShelfBook;
import com.myshelf.book.domain.UserBook;
import java.time.Instant;
import java.util.UUID;

/** 서재에 배치된 도서 정보와 해당 연결의 메모·위치·등록 시각을 담는다. */
public record ShelfBookItem(
    UUID shelfBookId,
    UUID userBookId,
    String isbn,
    String title,
    String author,
    String publisher,
    String coverImageUrl,
    ReadingStatus readingStatus,
    String memo,
    Integer position,
    Instant createdAt,
    Instant updatedAt) {
  public static ShelfBookItem from(ShelfBook shelfBook, UserBook userBook) {
    return new ShelfBookItem(
        shelfBook.id(),
        userBook.id(),
        userBook.isbn(),
        userBook.title(),
        userBook.author(),
        userBook.publisher(),
        userBook.coverImageUrl(),
        userBook.readingStatus(),
        shelfBook.memo(),
        shelfBook.position(),
        shelfBook.createdAt(),
        shelfBook.updatedAt());
  }
}
