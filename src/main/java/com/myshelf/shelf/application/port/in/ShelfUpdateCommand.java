package com.myshelf.shelf.application.port.in;

import jakarta.annotation.Nullable;
import java.util.UUID;

public record ShelfUpdateCommand(
    UUID userId, UUID shelfId, String name, @Nullable String description) {}
