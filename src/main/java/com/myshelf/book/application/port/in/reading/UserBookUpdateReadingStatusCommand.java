package com.myshelf.book.application.port.in.reading;

import com.myshelf.book.domain.ReadingStatus;
import java.util.UUID;

public record UserBookUpdateReadingStatusCommand(
    UUID userId, UUID userBookId, ReadingStatus readingStatus) {}
