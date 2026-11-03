package com.myshelf.book.application.port.out.search;

import com.myshelf.book.application.model.BookSearchState;
import java.util.Optional;
import java.util.UUID;

public interface BookSearchStatePort {
  void save(UUID searchId, BookSearchState state);

  Optional<BookSearchState> findById(UUID searchId);

  void delete(UUID searchId);
}
