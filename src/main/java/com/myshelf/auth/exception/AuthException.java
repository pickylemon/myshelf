package com.myshelf.auth.exception;

import com.myshelf.auth.error.AuthErrorCode;
import lombok.Getter;

@Getter
public class AuthException extends RuntimeException {
  private final AuthErrorCode errorCode;

  public AuthException(AuthErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
