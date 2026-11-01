package com.myshelf.book.adapter.in.web;

import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class BookControllerAdvice {

  @ExceptionHandler(BookException.class)
  public ProblemDetail handleBookException(BookException e) {
    BookErrorCode errorCode = e.getErrorCode();
    HttpStatus httpStatus =
        switch (errorCode) {
          case SEARCH_SESSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
          case SEARCH_REQUEST_IN_PROGRESS -> HttpStatus.CONFLICT;
          case EXTERNAL_BOOK_SEARCH_FAILED, INVALID_EXTERNAL_BOOK_RESPONSE ->
              HttpStatus.BAD_GATEWAY;
          case EXTERNAL_BOOK_SEARCH_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
          case SEARCH_SESSION_STORE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };

    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(httpStatus, errorCode.getMessage());
    problemDetail.setTitle("Book Error");
    problemDetail.setProperty("code", errorCode.getCode());
    return problemDetail;
  }
}
