package com.myshelf.book.application.service;

import com.myshelf.book.adapter.out.memory.BookSearchState;
import com.myshelf.book.application.dto.BookSearchItem;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.dto.BookShelfInfo;
import com.myshelf.book.application.dto.ExternalBookFetchResult;
import com.myshelf.book.application.dto.ExternalBookSearchResult;
import com.myshelf.book.application.port.in.ExternalBookSearchUseCase;
import com.myshelf.book.application.port.out.BookRegistrationLookupPort;
import com.myshelf.book.application.port.out.BookSearchField;
import com.myshelf.book.application.port.out.BookSearchStatePort;
import com.myshelf.book.application.port.out.ExternalBookSearchPort;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExternalBookSearchService implements ExternalBookSearchUseCase {
  private final ExternalBookSearchPort externalBookSearchPort;
  private final BookSearchStatePort bookSearchStatePort;
  private final BookRegistrationLookupPort bookRegistrationLookupPort;

  private static final int SEARCH_PAGE_SIZE = 20;
  private static final List<String> TARGET_SEARCH_FIELDS = List.of("title", "author", "publisher");

  @Override
  public ExternalBookSearchResult search(UUID userId, String keyword) {
    UUID searchId = UUID.randomUUID();

    // 1. 검색 세션 초기 상태 구성
    Map<BookSearchField, Integer> initialPages =
        EnumSet.allOf(BookSearchField.class).stream()
            .collect(Collectors.toMap(Function.identity(), field -> 1));

    BookSearchState initialState =
        new BookSearchState(userId, keyword, List.of(), Set.of(), initialPages);

    // 2. 검색 결과 반환
    return loadSearchPage(userId, searchId, initialState);
  }

  @Override
  public ExternalBookSearchResult loadMore(UUID userId, UUID searchId) {
    // 1. 공용 메모리에서 기존 도서 검색 상태 조회 (및 소유자 검증)
    BookSearchState state =
        bookSearchStatePort
            .findById(searchId)
            .filter(found -> found.userId().equals(userId))
            .orElseThrow(
                () -> new IllegalArgumentException("유효하지 않은 검색 세션입니다.") // TODO 커스텀 예외로 변환 여부
                );

    // 2. 검색 결과 반환
    return loadSearchPage(userId, searchId, state);
  }

  private ExternalBookSearchResult loadSearchPage(
      UUID userId, UUID searchId, BookSearchState state) {
    // 1. 갱신 가능한 컬렉션 준비
    List<BookSearchMeta> pendingBooks = new ArrayList<>(state.pendingBooks());
    Set<String> seenIsbns = new HashSet<>(state.seenIsbns());
    Map<BookSearchField, Integer> nextPageByField = new LinkedHashMap<>(state.nextPageByField());

    // 2. 반환할 도서가 부족하면 외부 API 추가 호출
    while (pendingBooks.size() < SEARCH_PAGE_SIZE && !nextPageByField.isEmpty()) {

      ExternalBookFetchResult fetched =
          externalBookSearchPort.search(state.keyword(), nextPageByField);

      // 검색 세션 전체에서 중복 isbn 제거
      for (BookSearchMeta book : fetched.books()) {
        if (seenIsbns.add(book.isbn())) {
          pendingBooks.add(book);
        }
      }

      // 필드별 다음 호출 외부 api page 갱신
      nextPageByField = new LinkedHashMap<>(fetched.nextPageByField());
    }

    // 3. 클라이언트에 반환할 도서 추출
    int endIndex = Math.min(SEARCH_PAGE_SIZE, pendingBooks.size());

    List<BookSearchMeta> selectedBooks = List.copyOf(pendingBooks.subList(0, endIndex));

    // 4. 아직 반환하지 않은 도서 보관
    List<BookSearchMeta> remainingBooks =
        List.copyOf(pendingBooks.subList(endIndex, pendingBooks.size()));

    // 5. hasNext 판단
    boolean hasNext = !remainingBooks.isEmpty() || !nextPageByField.isEmpty();

    // 6. 반환대상 도서의 ISBN만 수집 및 DB 조회
    List<String> isbns = selectedBooks.stream().map(BookSearchMeta::isbn).toList();

    Map<String, List<BookShelfInfo>> registeredInfos =
        isbns.isEmpty() ? Map.of() : bookRegistrationLookupPort.lookup(userId, isbns);

    // 7. 외부 도서 정보와 사용자 등록 도서 정보 merge
    List<BookSearchItem> books =
        selectedBooks.stream()
            .map(book -> new BookSearchItem(book, registeredInfos.get(book.isbn())))
            .toList();

    // 8. 갱신된 검색 상태 저장
    if (hasNext) {
      BookSearchState updatedState =
          new BookSearchState(userId, state.keyword(), remainingBooks, seenIsbns, nextPageByField);

      bookSearchStatePort.save(searchId, updatedState);
    } else {
      bookSearchStatePort.delete(searchId);
    }

    // 9. 최종 검색 결과 반환
    return new ExternalBookSearchResult(books, hasNext);
  }
}
