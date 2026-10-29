package com.myshelf.book.application.port.in;

import com.myshelf.book.application.dto.ExternalBookSearchResult;

public interface ExternalBookSearchUseCase {
  ExternalBookSearchResult search(String keyword, int page);
}
