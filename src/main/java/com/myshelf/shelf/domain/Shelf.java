package com.myshelf.shelf.domain;

import java.time.Instant;
import java.util.UUID;

public record Shelf(
    UUID id, String name, String description, UUID userId, Instant createdAt, Instant updatedAt) {

  // 신규 Shelf 생성용
  public Shelf(UUID id, String name, String description, UUID userId) {
    this(id, name, description, userId, null, null);
  }
}
