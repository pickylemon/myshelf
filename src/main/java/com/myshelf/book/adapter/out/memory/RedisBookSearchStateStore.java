package com.myshelf.book.adapter.out.memory;

import com.myshelf.book.application.port.out.BookSearchStatePort;
import java.util.Optional;
import java.util.UUID;

public class RedisBookSearchStateStore implements BookSearchStatePort {
  @Override
  public void save(UUID searchId, BookSearchState state) {}

  @Override
  public Optional<BookSearchState> findById(UUID searchId) {
    return Optional.empty();
  }

  @Override
  public void delete(UUID searchId) {}
}
