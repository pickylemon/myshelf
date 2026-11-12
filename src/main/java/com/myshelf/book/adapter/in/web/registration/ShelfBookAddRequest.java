package com.myshelf.book.adapter.in.web.registration;

import com.myshelf.book.application.port.in.registration.ShelfBookAddCommand;
import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.global.util.DuplicateChecker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ShelfBookAddRequest(@NotEmpty List<@NotNull @Valid BookInput> books) {
  public ShelfBookAddCommand toCommand(UUID userId, UUID shelfId) {
    return new ShelfBookAddCommand(
        userId,
        shelfId,
        books.stream()
            .map(book -> new ShelfBookAddCommand.BookInput(book.isbn(), book.readingStatus()))
            .toList());
  }

  public List<String> getValidIsbnList() {
    return this.books().stream().filter(Objects::nonNull).map(BookInput::isbn).toList();
  }

  public record BookInput(@NotBlank String isbn, @NotNull ReadingStatus readingStatus) {}

  @AssertTrue(message = "요청에 중복된 isbn의 도서가 있습니다.")
  public boolean isIsbnUnique() {
    if (books == null) {
      return true; // 목록 누락은 @NotEmpty가 검증한다.
    }
    return DuplicateChecker.isUnique(this.getValidIsbnList());
  }
}
