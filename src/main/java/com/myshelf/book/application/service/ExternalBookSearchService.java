package com.myshelf.book.application.service;

import com.myshelf.book.application.dto.BookSearchItem;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.dto.BookShelfInfo;
import com.myshelf.book.application.dto.ExternalBookFetchResult;
import com.myshelf.book.application.dto.ExternalBookSearchResult;
import com.myshelf.book.application.model.BookSearchState;
import com.myshelf.book.application.port.in.ExternalBookSearchUseCase;
import com.myshelf.book.application.port.out.BookMetadataCachePort;
import com.myshelf.book.application.port.out.BookRegistrationLookupPort;
import com.myshelf.book.application.port.out.BookSearchField;
import com.myshelf.book.application.port.out.BookSearchStatePort;
import com.myshelf.book.application.port.out.ExternalBookSearchPort;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalBookSearchService implements ExternalBookSearchUseCase {
  private final ExternalBookSearchPort externalBookSearchPort;
  private final BookSearchStatePort bookSearchStatePort;
  private final BookRegistrationLookupPort bookRegistrationLookupPort;
  private final BookMetadataCachePort bookMetadataCachePort;

  private static final int SEARCH_PAGE_SIZE = 20;
  private static final int MAX_FETCH_ROUNDS = 2;

  // 단일 서버에서 동일 사용자의 같은 검색 요청을 보호한다.
  // TODO: 다중 서버 간 동시 요청을 막는 Redis 분산 잠금은 아직 미구현.
  private final Set<SearchRequestKey> processingSearches = ConcurrentHashMap.newKeySet();

  private record SearchRequestKey(UUID userId, UUID searchId) {}

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
    SearchRequestKey key = new SearchRequestKey(userId, searchId);
    if (!processingSearches.add(key)) {
      throw new BookException(BookErrorCode.SEARCH_REQUEST_IN_PROGRESS);
    }

    try {
      // 중복 요청을 막은 뒤 최신 상태를 읽고, 소유권 확인부터 상태 저장까지 보호한다.
      BookSearchState state =
          bookSearchStatePort
              .findById(searchId)
              .filter(found -> found.userId().equals(userId))
              .orElseThrow(() -> new BookException(BookErrorCode.SEARCH_SESSION_NOT_FOUND));

      return loadSearchPage(userId, searchId, state);
    } finally {
      processingSearches.remove(key);
    }
  }

  private ExternalBookSearchResult loadSearchPage(
      UUID userId, UUID searchId, BookSearchState state) {
    // 1. 갱신 가능한 컬렉션 준비
    List<BookSearchMeta> pendingBooks = new ArrayList<>(state.pendingBooks());
    Set<String> seenIsbns = new HashSet<>(state.seenIsbns());
    Map<BookSearchField, Integer> nextPageByField = new LinkedHashMap<>(state.nextPageByField());

    // 2. 반환할 도서가 부족하면 외부 API 추가 호출
    int fetchRounds = 0;
    while (pendingBooks.size() < SEARCH_PAGE_SIZE
        && !nextPageByField.isEmpty()
        && fetchRounds < MAX_FETCH_ROUNDS) {

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
      fetchRounds++;
    }

    // 3. 클라이언트에 반환할 도서 추출
    int endIndex = Math.min(SEARCH_PAGE_SIZE, pendingBooks.size());

    List<BookSearchMeta> selectedBooks = List.copyOf(pendingBooks.subList(0, endIndex));

    // 4. 아직 반환하지 않은 도서 보관
    List<BookSearchMeta> remainingBooks =
        List.copyOf(pendingBooks.subList(endIndex, pendingBooks.size()));

    // 5. hasNext 판단
    // 조회 제한으로 빈 결과와 hasNext=true가 함께 반환되어 '더 보기' UX가 어색할 수 있다.
    boolean hasNext = !remainingBooks.isEmpty() || !nextPageByField.isEmpty();

    // 6. 반환대상 도서의 ISBN만 수집 및 DB 조회
    List<String> isbns = selectedBooks.stream().map(BookSearchMeta::isbn).toList();

    Map<String, List<BookShelfInfo>> registeredInfos =
        isbns.isEmpty() ? Map.of() : bookRegistrationLookupPort.lookup(userId, isbns);

    // 7. 외부 도서 정보와 사용자 등록 도서 정보 merge
    List<BookSearchItem> books =
        selectedBooks.stream()
            .map(
                book ->
                    new BookSearchItem(book, registeredInfos.getOrDefault(book.isbn(), List.of())))
            .toList();

    // 8. 갱신된 검색 상태 저장
    if (hasNext) {
      BookSearchState updatedState =
          new BookSearchState(userId, state.keyword(), remainingBooks, seenIsbns, nextPageByField);

      bookSearchStatePort.save(searchId, updatedState);
    } else {
      bookSearchStatePort.delete(searchId);
    }

    // 등록용 메타데이터 저장
    cacheReturnedBooks(selectedBooks);

    // 9. 최종 검색 결과 반환
    return new ExternalBookSearchResult(books, hasNext, searchId);
  }

  private void cacheReturnedBooks(List<BookSearchMeta> selectedBooks) {
    try {
      selectedBooks.forEach(bookMetadataCachePort::save);
    } catch (BookException e) {
      if (e.getErrorCode() != BookErrorCode.BOOK_METADATA_CACHE_UNAVAILABLE) {
        throw e;
      }
      log.warn("도서정보를 Redis 캐시에 저장하지 못했습니다. 오류 코드: {}", e.getErrorCode());
    }
  }
}
