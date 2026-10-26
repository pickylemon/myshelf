package com.myshelf.shelf.adapter.in.web;

import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ShelfControllerAdvice {
  @ExceptionHandler(ShelfException.class)
  public ResponseEntity<ProblemDetail> handleShelfException(ShelfException e) {
    ShelfErrorCode errorCode = e.getErrorCode();

    HttpStatus httpStatus =
        switch (errorCode) {
          case DUPLICATE_NAME -> HttpStatus.CONFLICT;
          case INVALID_SHELF_OWNER -> HttpStatus.UNAUTHORIZED;
          case SHELF_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };

    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(httpStatus, errorCode.getMessage());

    problemDetail.setTitle("Shelf Error");
    problemDetail.setProperty("code", errorCode.name());

    ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(httpStatus);
    if (httpStatus == HttpStatus.UNAUTHORIZED) {
      responseBuilder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    return responseBuilder.body(problemDetail);
  }
}
