package com.myshelf.shelf.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class Shelf {
  private final UUID id;
  private final String name;
  private final String description;
  private final UUID userId;
  private final Instant createdAt;
  private final Instant updatedAt;

  // 신규 Shelf 생성용
  public Shelf(UUID id, String name, String description, UUID userId) {
    this(id, name, description, userId, null, null);
  }
}
