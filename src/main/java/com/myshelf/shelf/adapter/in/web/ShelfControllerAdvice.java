package com.myshelf.shelf.adapter.in.web;

import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ShelfControllerAdvice {
  @ExceptionHandler(ShelfException.class)
  public ProblemDetail handleShelfException(ShelfException e) {
    ShelfErrorCode errorCode = e.getErrorCode();

    HttpStatus httpStatus =
        switch (errorCode) {
          case DUPLICATE_NAME -> HttpStatus.CONFLICT;
        };

    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(httpStatus, errorCode.getMessage());

    problemDetail.setTitle("Shelf Error");
    problemDetail.setProperty("code", errorCode.name());

    return problemDetail;
  }
}
