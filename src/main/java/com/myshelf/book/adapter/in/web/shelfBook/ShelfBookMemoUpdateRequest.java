package com.myshelf.book.adapter.in.web.shelfBook;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;

public record ShelfBookMemoUpdateRequest(@Nullable @Size(max = 2_000) String memo) {}
