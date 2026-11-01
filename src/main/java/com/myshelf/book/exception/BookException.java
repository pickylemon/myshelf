package com.myshelf.book.exception;

import com.myshelf.book.error.BookErrorCode;
import lombok.Getter;

@Getter
public class BookException extends RuntimeException {
  private final BookErrorCode errorCode;

  public BookException(BookErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }

  public BookException(BookErrorCode errorCode, Throwable cause) {
    super(errorCode.getMessage(), cause);
    this.errorCode = errorCode;
  }
}
