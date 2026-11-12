package com.myshelf.book.application.port.in.shelf;

import java.util.List;
import java.util.UUID;

public record ShelfBookDeleteCommand(UUID userId, List<UUID> shelfBookIds) {}
