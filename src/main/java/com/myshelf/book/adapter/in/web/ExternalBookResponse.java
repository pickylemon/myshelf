package com.myshelf.book.adapter.in.web;

import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.shelf.adapter.in.web.ShelfResponse;
import java.time.LocalDate;
import java.util.List;

public record ExternalBookResponse(
    String isbn,
    String title,
    String author,
    String publisher,
    LocalDate publicationDate,
    String coverImageUrl,
    ReadingStatus readingStatus,
    List<ShelfResponse> registeredShelves) {}
