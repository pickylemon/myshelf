package com.myshelf.shelf.adapter.in.web;

import com.myshelf.shelf.domain.Shelf;
import java.time.Instant;
import java.util.UUID;

public record ShelfResponse(
    UUID id, String name, String description, Instant createdAt, Instant updatedAt) {

  public static ShelfResponse from(Shelf shelf) {
    return new ShelfResponse(
        shelf.id(), shelf.name(), shelf.description(), shelf.createdAt(), shelf.updatedAt());
  }
}
