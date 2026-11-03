package com.myshelf.book.domain;

import java.time.Instant;
import java.util.UUID;

public record ShelfBook(
    UUID id, UUID shelfId, UUID userBookId, Integer position, Instant createdAt) {

  // 신규 ShelfBook 생성용
  public ShelfBook(UUID id, UUID shelfId, UUID userBookId) {
    this(id, shelfId, userBookId, null, null);
  }
}
