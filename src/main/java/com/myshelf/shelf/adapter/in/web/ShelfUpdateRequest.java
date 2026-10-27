package com.myshelf.shelf.adapter.in.web;

import com.myshelf.shelf.application.port.in.ShelfUpdateCommand;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ShelfUpdateRequest(
    @NotBlank @Size(max = 255) String name, @Nullable @Size(max = 255) String description) {
  public ShelfUpdateCommand toCommand(UUID userId, UUID shelfId) {
    return new ShelfUpdateCommand(userId, shelfId, name, description);
  }
}
