package com.myshelf.book.domain;

import java.time.Instant;
import java.util.UUID;

public record UserBook(
    UUID id,
    UUID userId,
    String isbn,
    String title,
    String author,
    String publisher,
    String coverImageUrl,
    ReadingStatus readingStatus,
    String memo,
    Instant createdAt,
    Instant updatedAt) {
  public UserBook(
      UUID id,
      UUID userId,
      String isbn,
      String title,
      String author,
      String publisher,
      String coverImageUrl,
      ReadingStatus readingStatus) {

    this(
        id,
        userId,
        isbn,
        title,
        author,
        publisher,
        coverImageUrl,
        readingStatus,
        null, // memo
        null, // createdAt
        null // updatedAt
        );
  }
}
