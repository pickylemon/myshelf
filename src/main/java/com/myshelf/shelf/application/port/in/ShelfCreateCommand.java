package com.myshelf.shelf.application.port.in;

import jakarta.annotation.Nullable;
import java.util.UUID;

public record ShelfCreateCommand(UUID userId, String name, @Nullable String description) {}
