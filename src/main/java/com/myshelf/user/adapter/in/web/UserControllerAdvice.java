package com.myshelf.user.adapter.in.web;

import com.myshelf.user.error.UserErrorCode;
import com.myshelf.user.exception.UserException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UserControllerAdvice {
  @ExceptionHandler(UserException.class)
  public ProblemDetail handleUserException(UserException e) {
    UserErrorCode errorCode = e.getErrorCode();

    HttpStatus httpStatus =
        switch (errorCode) {
          case DUPLICATE_EMAIL -> HttpStatus.CONFLICT;
        };

    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(httpStatus, errorCode.getMessage());

    problemDetail.setTitle("User Error");
    problemDetail.setProperty("code", errorCode.name());

    return problemDetail;
  }
}
