package com.myshelf.global.persistence;

import java.util.Locale;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConstraintViolationMatcher {

  /** 제약 종류를 확인한 뒤, 이름에 부가 정보가 포함된 경우도 대소문자 구분 없이 판별한다. */
  public static boolean matches(
      Throwable exception, ConstraintKind kind, String expectedConstraintName) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        String name = violation.getConstraintName();
        return violation.getKind() == kind
            && name != null
            && name.toUpperCase(Locale.ROOT)
                .contains(expectedConstraintName.toUpperCase(Locale.ROOT));
      }
    }
    return false;
  }
}
