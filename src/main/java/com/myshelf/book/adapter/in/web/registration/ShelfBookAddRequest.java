package com.myshelf.book.adapter.in.web.registration;

import com.myshelf.book.application.port.in.registration.ShelfBookAddCommand;
import com.myshelf.book.domain.ReadingStatus;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
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

  public record BookInput(@NotBlank String isbn, @Nullable ReadingStatus readingStatus) {}
}
