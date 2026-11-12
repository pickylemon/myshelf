package com.myshelf.book.application.dto;

import java.util.UUID;

public record AddedBook(String isbn, UUID shelfBookId) {}
