package com.myshelf.book.application.dto;

import com.myshelf.book.application.port.out.search.BookSearchField;
import java.util.List;
import java.util.Map;

public record ExternalBookFetchResult(
    Map<BookSearchField, Integer> nextPageByField, List<BookSearchMeta> books) {}
