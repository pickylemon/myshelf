package com.myshelf.shelf.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ShelfErrorCode {
  DUPLICATE_NAME("DUPLICATE_NAME", "존재하는 Shelf 이름입니다.");

  private final String code;
  private final String message;
}
