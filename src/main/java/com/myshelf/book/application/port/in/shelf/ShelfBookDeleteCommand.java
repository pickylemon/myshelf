package com.myshelf.book.application.port.in.shelf;

import java.util.UUID;

public record ShelfBookDeleteCommand(UUID userId, UUID shelfBookId) {}
