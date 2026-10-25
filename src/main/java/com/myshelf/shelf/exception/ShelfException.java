package com.myshelf.shelf.exception;

import com.myshelf.shelf.error.ShelfErrorCode;
import lombok.Getter;

@Getter
public class ShelfException extends RuntimeException {
  private final ShelfErrorCode errorCode;

  public ShelfException(ShelfErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
