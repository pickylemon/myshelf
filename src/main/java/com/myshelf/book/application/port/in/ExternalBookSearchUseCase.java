package com.myshelf.book.application.port.in;

import com.myshelf.book.application.dto.ExternalBookSearchResult;
import java.util.UUID;

public interface ExternalBookSearchUseCase {
  ExternalBookSearchResult search(UUID userId, String keyword);

  ExternalBookSearchResult loadMore(UUID userId, UUID searchId);
}
