package com.myshelf.book.application.port.out.metadata;

import com.myshelf.book.application.dto.BookSearchMeta;
import java.util.Optional;

public interface BookMetadataCachePort {
  Optional<BookSearchMeta> findByIsbn(String isbn);

  void save(BookSearchMeta book);
}
