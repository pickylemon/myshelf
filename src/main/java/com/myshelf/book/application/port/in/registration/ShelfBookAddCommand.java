package com.myshelf.book.application.port.in.registration;

import com.myshelf.book.domain.ReadingStatus;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public record ShelfBookAddCommand(UUID userId, UUID shelfId, List<BookInput> books) {
  public ShelfBookAddCommand {
    books = List.copyOf(books);
  }

  /** readingStatus가 null이면 기존 도서는 상태 유지, 신규 도서는 WANT_TO_READ를 적용한다. */
  public record BookInput(String isbn, @Nullable ReadingStatus readingStatus) {}
}
