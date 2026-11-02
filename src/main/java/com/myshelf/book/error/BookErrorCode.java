package com.myshelf.book.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BookErrorCode {
  SEARCH_SESSION_NOT_FOUND("SEARCH_SESSION_NOT_FOUND", "도서 검색을 이어갈 수 없습니다. 다시 검색해 주세요."),
  SEARCH_REQUEST_IN_PROGRESS("SEARCH_REQUEST_IN_PROGRESS", "도서 검색 요청을 처리 중입니다. 잠시 후 다시 시도해 주세요."),
  EXTERNAL_BOOK_SEARCH_FAILED("EXTERNAL_BOOK_SEARCH_FAILED", "외부 도서 검색 중 오류가 발생했습니다."),
  EXTERNAL_BOOK_SEARCH_TIMEOUT("EXTERNAL_BOOK_SEARCH_TIMEOUT", "외부 도서 검색 응답 시간이 초과되었습니다."),
  INVALID_EXTERNAL_BOOK_RESPONSE("INVALID_EXTERNAL_BOOK_RESPONSE", "외부 도서 검색 응답을 처리할 수 없습니다."),
  SEARCH_SESSION_STORE_UNAVAILABLE(
      "SEARCH_SESSION_STORE_UNAVAILABLE", "검색 세션 저장소에 일시적으로 접근할 수 없습니다."),
  BOOK_METADATA_CACHE_UNAVAILABLE("BOOK_METADATA_CACHE_UNAVAILABLE", "도서정보 캐시에 일시적으로 접근할 수 없습니다."),
  BOOK_METADATA_NOT_FOUND("BOOK_METADATA_NOT_FOUND", "해당 ISBN의 도서정보를 찾을 수 없습니다.");

  private final String code;
  private final String message;
}
