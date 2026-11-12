package com.myshelf.book.adapter.in.web.search;

import com.myshelf.book.application.dto.BookSearchItem;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.dto.BookShelfInfo;
import java.time.LocalDate;
import java.util.List;

public record ExternalBookResponse(
    String isbn,
    String title,
    String author,
    String publisher,
    LocalDate publicationDate,
    String coverImageUrl,
    List<RegisteredShelfResponse> registeredShelves) {
  public static ExternalBookResponse from(BookSearchItem item) {
    BookSearchMeta meta = item.bookInfo();
    List<BookShelfInfo> shelves = item.registeredShelves();
    List<RegisteredShelfResponse> registeredShelves =
        shelves.stream().map(RegisteredShelfResponse::from).toList();

    return new ExternalBookResponse(
        meta.isbn(),
        meta.title(),
        meta.author(),
        meta.publisher(),
        meta.publicationDate(),
        meta.coverImageUrl(),
        registeredShelves);
  }
}
