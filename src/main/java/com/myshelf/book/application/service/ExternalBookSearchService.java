package com.myshelf.book.application.service;

import com.myshelf.book.application.dto.ExternalBookSearchResult;
import com.myshelf.book.application.port.in.ExternalBookSearchUseCase;
import com.myshelf.book.application.port.out.ExternalBookSearchPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExternalBookSearchService implements ExternalBookSearchUseCase {
  private final ExternalBookSearchPort externalBookSearchPort;

  @Override
  public ExternalBookSearchResult search(String keyword, int page) {}
}
