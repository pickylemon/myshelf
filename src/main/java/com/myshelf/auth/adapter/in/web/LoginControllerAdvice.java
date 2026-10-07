package com.myshelf.auth.adapter.in.web;

import com.myshelf.auth.error.AuthErrorCode;
import com.myshelf.auth.exception.AuthException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// TODO user 패키지의 controllerAdvice와 중복 제거 가능?
@RestControllerAdvice
public class LoginControllerAdvice {
  @ExceptionHandler(AuthException.class)
  public ResponseEntity<ProblemDetail> handleAuthException(AuthException e) {
    AuthErrorCode errorCode = e.getErrorCode();

    HttpStatus httpStatus =
        switch (errorCode) {
          case INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
        };

    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(httpStatus, errorCode.getMessage());

    problemDetail.setTitle("Auth Error");
    problemDetail.setProperty("code", errorCode.name());

    ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(httpStatus);

    if (httpStatus == HttpStatus.UNAUTHORIZED) {
      responseBuilder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    return responseBuilder.body(problemDetail);
  }
}
