package com.myshelf.user.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum UserErrorCode {
  DUPLICATE_EMAIL("DUPLICATE_EMAIL", "가입된 이메일입니다"),
  USER_NOT_FOUND("USER_NOT_FOUND", "회원을 찾을 수 없습니다");

  private final String code;
  private final String message;
}
