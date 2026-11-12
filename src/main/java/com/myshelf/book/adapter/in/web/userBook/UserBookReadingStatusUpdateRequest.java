package com.myshelf.book.adapter.in.web.userBook;

import com.myshelf.book.domain.ReadingStatus;
import jakarta.validation.constraints.NotNull;

public record UserBookReadingStatusUpdateRequest(@NotNull ReadingStatus readingStatus) {}
