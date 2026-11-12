package com.myshelf.book.application.model;

import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.port.out.search.BookSearchField;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record BookSearchState(
    UUID userId,
    String keyword,
    List<BookSearchMeta> pendingBooks,
    Set<String> seenIsbns,
    Map<BookSearchField, Integer> nextPageByField) {
  public BookSearchState {
    pendingBooks = List.copyOf(pendingBooks); // 조회했지만 아직 반환하지 않은 도서
    seenIsbns = Set.copyOf(seenIsbns); // 이미 검색으로 조회한 ISBN
    nextPageByField = Map.copyOf(nextPageByField); // field별 다음 요청할 api page
  }
}
