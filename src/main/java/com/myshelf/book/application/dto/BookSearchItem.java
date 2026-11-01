package com.myshelf.book.application.dto;

import java.util.List;

public record BookSearchItem(BookSearchMeta bookInfo, List<BookShelfInfo> registeredShelves) {}
