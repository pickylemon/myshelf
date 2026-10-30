package com.myshelf.book.application.dto;

import java.util.List;

public record BookSearchItem(BookSearchMeta books, List<BookShelfInfo> registeredShelves) {}
