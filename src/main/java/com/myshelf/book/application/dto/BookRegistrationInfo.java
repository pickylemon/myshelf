package com.myshelf.book.application.dto;

import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.shelf.adapter.in.web.ShelfResponse;
import java.util.List;

public record BookRegistrationInfo(
    ReadingStatus readingStatus, List<ShelfResponse> registeredShelves) {}
