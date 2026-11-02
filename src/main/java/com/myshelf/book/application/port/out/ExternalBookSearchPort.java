package com.myshelf.book.application.port.out;

import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.dto.ExternalBookFetchResult;
import java.util.Map;
import java.util.Optional;

public interface ExternalBookSearchPort {
  ExternalBookFetchResult search(String keyword, Map<BookSearchField, Integer> nextPageByField);

  Optional<BookSearchMeta> findByIsbn(String isbn);
}
