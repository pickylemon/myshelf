package com.myshelf.shelf.adapter.in.web;

import com.myshelf.shelf.application.port.in.ShelfCreateCommand;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ShelfCreateRequest(
    @NotBlank @Size(max = 255) String name, @Nullable @Size(max = 255) String description) {
  public ShelfCreateCommand toCommand(UUID userId) {
    return new ShelfCreateCommand(userId, name, description);
  }
}
