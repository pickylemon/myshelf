package com.myshelf.book.adapter.in.web;

import com.myshelf.book.application.dto.BookShelfInfo;
import java.util.UUID;

public record RegisteredShelfResponse(UUID shelfId, String shelfName) {
  public static RegisteredShelfResponse from(BookShelfInfo bookShelfInfo) {
    return new RegisteredShelfResponse(bookShelfInfo.shelfId(), bookShelfInfo.shelfName());
  }
}
