package com.myshelf.book.adapter.out.external;

import java.time.LocalDate;

public record NationalLibraryBookResponse(
    String isbn,
    String title,
    String author,
    String publisher,
    LocalDate publicationDate,
    String coverImageUrl) {}
