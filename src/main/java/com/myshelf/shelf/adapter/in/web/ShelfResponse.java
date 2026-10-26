package com.myshelf.shelf.adapter.in.web;

import com.myshelf.shelf.domain.Shelf;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ShelfResponse {
  private UUID id;
  private String name;
  private String description;
  private Instant createdAt;
  private Instant updatedAt;

  public static ShelfResponse from(Shelf shelf) {
    return new ShelfResponse(
        shelf.getId(),
        shelf.getName(),
        shelf.getDescription(),
        shelf.getCreatedAt(),
        shelf.getUpdatedAt());
  }
}
