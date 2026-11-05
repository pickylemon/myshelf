package com.myshelf.book.application.port.in.shelf;

import java.util.UUID;

public record ShelfBookUpdateCommand(UUID userId, UUID shelfBookId, String memo) {}
