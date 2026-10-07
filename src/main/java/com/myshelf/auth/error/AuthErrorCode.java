package com.myshelf.auth.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode {
  INVALID_CREDENTIALS("INVALID_CREDENTIALS", "잘못된 로그인 정보");

  private final String code;
  private final String message;
}
