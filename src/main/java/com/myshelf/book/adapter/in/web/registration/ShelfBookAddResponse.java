package com.myshelf.book.adapter.in.web.registration;

import com.myshelf.book.application.dto.AddedBook;
import java.util.List;
import java.util.UUID;

public record ShelfBookAddResponse(List<AddedBookResponse> books) {
  public ShelfBookAddResponse {
    books = List.copyOf(books);
  }

  public static ShelfBookAddResponse from(List<AddedBook> addedBooks) {
    return new ShelfBookAddResponse(
        addedBooks.stream()
            .map(book -> new AddedBookResponse(book.isbn(), book.shelfBookId()))
            .toList());
  }

  public record AddedBookResponse(String isbn, UUID shelfBookId) {}
}
