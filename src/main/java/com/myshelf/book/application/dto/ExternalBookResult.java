package com.myshelf.book.application.dto;

import java.time.LocalDate;

public record ExternalBookResult(
    String isbn,
    String title,
    String author,
    String publisher,
    LocalDate publicationDate,
    String coverImageUrl) {}
