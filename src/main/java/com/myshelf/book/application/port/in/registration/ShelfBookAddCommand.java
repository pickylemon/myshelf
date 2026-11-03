package com.myshelf.book.application.port.in.registration;

import com.myshelf.book.domain.ReadingStatus;
import java.util.List;
import java.util.UUID;

public record ShelfBookAddCommand(UUID userId, UUID shelfId, List<BookInput> books) {
  public ShelfBookAddCommand {
    books = List.copyOf(books);
  }

  /** readingStatus는 필수이며, 신규·기존·재등록 도서 모두 요청한 상태를 적용한다. */
  public record BookInput(String isbn, ReadingStatus readingStatus) {}
}
