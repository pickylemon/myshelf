package com.myshelf.shelf.application.port.in;

import jakarta.annotation.Nullable;
import java.util.UUID;

public record ShelfUpdateCommand(
    UUID shelfId, UUID userId, String name, @Nullable String description) {}
