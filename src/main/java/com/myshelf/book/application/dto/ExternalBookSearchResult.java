package com.myshelf.book.application.dto;

import java.util.List;
import java.util.UUID;

public record ExternalBookSearchResult(
    List<BookSearchItem> books, boolean hasNext, UUID searchId) {}
