package com.myshelf.book.adapter.out.memory;

import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.port.out.BookMetadataCachePort;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!redis")
public class NoOpBookMetadataCache implements BookMetadataCachePort {
  @Override
  public Optional<BookSearchMeta> findByIsbn(String isbn) {
    return Optional.empty();
  }

  @Override
  public void save(BookSearchMeta book) {}
}
