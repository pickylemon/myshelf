package com.myshelf.user.exception;

import com.myshelf.user.error.UserErrorCode;
import lombok.Getter;

@Getter
public class UserException extends RuntimeException {
  private final UserErrorCode errorCode;

  public UserException(UserErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
