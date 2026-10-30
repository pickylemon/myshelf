package com.myshelf.book.application.dto;

import java.util.List;

public record ExternalBookSearchResult(List<BookSearchItem> books, boolean hasNext) {}
