package com.myshelf.book.application.port.out;

import com.myshelf.book.application.dto.ExternalBookFetchResult;
import java.util.Map;

public interface ExternalBookSearchPort {
  ExternalBookFetchResult search(String keyword, Map<BookSearchField, Integer> nextPageByField);
}
