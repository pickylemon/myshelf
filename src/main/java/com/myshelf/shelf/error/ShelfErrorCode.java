package com.myshelf.shelf.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ShelfErrorCode {
  DUPLICATE_NAME("DUPLICATE_NAME", "존재하는 Shelf 이름입니다."),
  INVALID_SHELF_OWNER("INVALID_SHELF_OWNER", "Shelf 소유자 정보를 확인할 수 없습니다."),
  SHELF_NOT_FOUND("SHELF_NOT_FOUND", "Shelf를 찾을 수 없습니다.");

  private final String code;
  private final String message;
}
