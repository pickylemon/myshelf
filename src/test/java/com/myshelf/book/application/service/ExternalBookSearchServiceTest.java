package com.myshelf.book.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.myshelf.book.adapter.out.memory.InMemoryBookSearchStateStore;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.dto.BookShelfInfo;
import com.myshelf.book.application.dto.ExternalBookFetchResult;
import com.myshelf.book.application.dto.ExternalBookSearchResult;
import com.myshelf.book.application.model.BookSearchState;
import com.myshelf.book.application.port.out.metadata.BookMetadataCachePort;
import com.myshelf.book.application.port.out.registration.BookRegistrationLookupPort;
import com.myshelf.book.application.port.out.search.BookSearchField;
import com.myshelf.book.application.port.out.search.BookSearchStatePort;
import com.myshelf.book.application.port.out.search.ExternalBookSearchPort;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.serializer.SerializationException;

/** 검색 세션 접근과 페이지 진행을 검증한다. 실제 Redis·외부 API·DB는 사용하지 않는다. */
@ExtendWith(MockitoExtension.class)
class ExternalBookSearchServiceTest {

  /** 캐시 연결 장애는 검색을 실패시키지 않으며 현재 캐시 저장 루프만 중단한다. */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 메타데이터_저장_장애여도_검색_응답과_세션_진행은_유지한다(boolean hasNext) {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    List<BookSearchMeta> pending =
        IntStream.range(0, hasNext ? 21 : 2)
            .mapToObj(i -> new BookSearchMeta("isbn-" + i, "제목", null, null, null, null))
            .toList();
    when(bookSearchStatePort.findById(searchId))
        .thenReturn(Optional.of(new BookSearchState(userId, "자바", pending, Set.of(), Map.of())));
    when(bookRegistrationLookupPort.lookup(eq(userId), any())).thenReturn(Map.of());
    doThrow(new BookException(BookErrorCode.BOOK_METADATA_CACHE_UNAVAILABLE))
        .when(bookMetadataCachePort)
        .save(pending.get(0));

    ExternalBookSearchResult result = service.loadMore(userId, searchId);

    assertThat(result.books()).hasSize(hasNext ? 20 : 2);
    assertThat(result.hasNext()).isEqualTo(hasNext);
    if (hasNext) verify(bookSearchStatePort).save(eq(searchId), any());
    else verify(bookSearchStatePort).delete(searchId);
    verify(bookMetadataCachePort).save(pending.get(0));
    verifyNoMoreInteractions(bookMetadataCachePort);
  }

  @Mock ExternalBookSearchPort externalBookSearchPort;
  @Mock BookSearchStatePort bookSearchStatePort;
  @Mock BookRegistrationLookupPort bookRegistrationLookupPort;
  @Mock BookMetadataCachePort bookMetadataCachePort;

  private ExternalBookSearchService service;

  @BeforeEach
  void setUp() {
    service =
        new ExternalBookSearchService(
            externalBookSearchPort,
            bookSearchStatePort,
            bookRegistrationLookupPort,
            bookMetadataCachePort);
  }

  /** 없는 세션(만료 포함)과 타인 소유 세션은 동일한 오류로 거부하고 상태를 변경하지 않는다. */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 세션이_없거나_타인_소유이면_검색을_진행하지_않는다(boolean ownedByOtherUser) {
    // given
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    Optional<BookSearchState> found =
        ownedByOtherUser
            ? Optional.of(
                new BookSearchState(
                    UUID.randomUUID(), "자바", List.of(), Set.of(), Map.of(BookSearchField.TITLE, 1)))
            : Optional.empty();
    when(bookSearchStatePort.findById(searchId)).thenReturn(found);

    // when, then
    assertThatThrownBy(() -> service.loadMore(userId, searchId))
        .isInstanceOfSatisfying(
            BookException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.SEARCH_SESSION_NOT_FOUND));

    verify(bookSearchStatePort).findById(searchId);
    verify(bookSearchStatePort, never()).save(any(), any());
    verify(bookSearchStatePort, never()).delete(any());
    verifyNoInteractions(externalBookSearchPort, bookRegistrationLookupPort);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 두_차례_조회_후_확보한_결과를_반환하고_다음_요청에서_이어서_조회한다(boolean hasNewBook) {
    // given
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    BookSearchMeta duplicate = new BookSearchMeta("seen", "기존 도서", null, null, null, null);
    BookSearchMeta newBook = new BookSearchMeta("new", "새 도서", null, null, null, null);
    BookSearchState state =
        new BookSearchState(
            userId, "자바", List.of(), Set.of("seen"), Map.of(BookSearchField.TITLE, 1));
    when(bookSearchStatePort.findById(searchId)).thenReturn(Optional.of(state));
    when(externalBookSearchPort.search("자바", Map.of(BookSearchField.TITLE, 1)))
        .thenReturn(
            new ExternalBookFetchResult(Map.of(BookSearchField.TITLE, 2), List.of(duplicate)));
    when(externalBookSearchPort.search("자바", Map.of(BookSearchField.TITLE, 2)))
        .thenReturn(
            new ExternalBookFetchResult(
                Map.of(BookSearchField.TITLE, 3), List.of(hasNewBook ? newBook : duplicate)));

    if (hasNewBook) {
      when(bookRegistrationLookupPort.lookup(userId, List.of("new"))).thenReturn(Map.of());
    }

    // when
    ExternalBookSearchResult result = service.loadMore(userId, searchId);

    // then: 20권 미만이거나 빈 결과여도 두 차례에서 멈추고 다음 위치를 저장한다.
    assertThat(result.books())
        .extracting(item -> item.bookInfo().isbn())
        .containsExactlyElementsOf(hasNewBook ? List.of("new") : List.of());
    assertThat(result.hasNext()).isTrue();
    assertThat(result.searchId()).isEqualTo(searchId);
    verify(externalBookSearchPort, times(2)).search(any(), any());
    ArgumentCaptor<BookSearchState> captor = ArgumentCaptor.forClass(BookSearchState.class);
    verify(bookSearchStatePort).save(eq(searchId), captor.capture());
    BookSearchState saved = captor.getValue();
    assertThat(saved.nextPageByField()).containsExactlyEntriesOf(Map.of(BookSearchField.TITLE, 3));
    assertThat(saved.pendingBooks()).isEmpty();
    assertThat(saved.seenIsbns())
        .containsExactlyInAnyOrderElementsOf(hasNewBook ? Set.of("seen", "new") : Set.of("seen"));
    verify(bookSearchStatePort, never()).delete(any());
    if (!hasNewBook) {
      verifyNoInteractions(bookRegistrationLookupPort);
    }

    // 다음 요청은 저장된 3페이지부터 이어서 조회하고, 소진되면 세션을 삭제한다.
    when(bookSearchStatePort.findById(searchId)).thenReturn(Optional.of(saved));
    when(externalBookSearchPort.search("자바", Map.of(BookSearchField.TITLE, 3)))
        .thenReturn(new ExternalBookFetchResult(Map.of(), List.of()));

    ExternalBookSearchResult next = service.loadMore(userId, searchId);

    assertThat(next.books()).isEmpty();
    assertThat(next.hasNext()).isFalse();
    verify(externalBookSearchPort).search("자바", Map.of(BookSearchField.TITLE, 1));
    verify(externalBookSearchPort).search("자바", Map.of(BookSearchField.TITLE, 2));
    verify(externalBookSearchPort).search("자바", Map.of(BookSearchField.TITLE, 3));
    verifyNoMoreInteractions(externalBookSearchPort);
    verify(bookSearchStatePort).delete(searchId);
  }

  @Test
  void 대기_도서가_20권이면_외부_조회_없이_반환한다() {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    List<BookSearchMeta> pending =
        IntStream.range(0, 20)
            .mapToObj(i -> new BookSearchMeta("isbn-" + i, "도서", null, null, null, null))
            .toList();
    Set<String> seen = pending.stream().map(BookSearchMeta::isbn).collect(Collectors.toSet());
    BookSearchState state =
        new BookSearchState(userId, "자바", pending, seen, Map.of(BookSearchField.TITLE, 3));
    when(bookSearchStatePort.findById(searchId)).thenReturn(Optional.of(state));
    when(bookRegistrationLookupPort.lookup(
            userId, pending.stream().map(BookSearchMeta::isbn).toList()))
        .thenReturn(Map.of());

    ExternalBookSearchResult result = service.loadMore(userId, searchId);

    assertThat(result.books())
        .extracting(item -> item.bookInfo())
        .containsExactlyElementsOf(pending);
    assertThat(result.hasNext()).isTrue();
    verifyNoInteractions(externalBookSearchPort);
  }

  @Test
  void 같은_검색의_동시_요청은_거절하고_완료_후에는_갱신된_상태로_조회한다() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    UUID otherSearchId = UUID.randomUUID();
    InMemoryBookSearchStateStore stateStore = new InMemoryBookSearchStateStore();
    ExternalBookSearchService concurrentService =
        new ExternalBookSearchService(
            externalBookSearchPort, stateStore, bookRegistrationLookupPort, bookMetadataCachePort);
    List<BookSearchMeta> pending =
        IntStream.range(0, 41)
            .mapToObj(i -> new BookSearchMeta("isbn-" + i, "도서", null, null, null, null))
            .toList();
    stateStore.save(
        searchId,
        new BookSearchState(
            userId,
            "자바",
            pending,
            pending.stream().map(BookSearchMeta::isbn).collect(Collectors.toSet()),
            Map.of()));
    stateStore.save(
        otherSearchId, new BookSearchState(userId, "다른 검색", List.of(), Set.of(), Map.of()));

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean firstLookup = new AtomicBoolean(true);
    when(bookRegistrationLookupPort.lookup(eq(userId), any()))
        .thenAnswer(
            invocation -> {
              if (firstLookup.getAndSet(false)) {
                started.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              }
              return Map.of();
            });

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<ExternalBookSearchResult> first =
          executor.submit(() -> concurrentService.loadMore(userId, searchId));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> concurrentService.loadMore(userId, searchId))
          .isInstanceOfSatisfying(
              BookException.class,
              ex ->
                  assertThat(ex.getErrorCode())
                      .isEqualTo(BookErrorCode.SEARCH_REQUEST_IN_PROGRESS));
      // 소유자가 다르면 처리 중 여부와 관계없이 기존 404 정책을 유지한다.
      assertThatThrownBy(() -> concurrentService.loadMore(UUID.randomUUID(), searchId))
          .isInstanceOfSatisfying(
              BookException.class,
              ex ->
                  assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.SEARCH_SESSION_NOT_FOUND));
      assertThat(concurrentService.loadMore(userId, otherSearchId).hasNext()).isFalse();

      release.countDown();
      ExternalBookSearchResult firstResult = first.get(5, TimeUnit.SECONDS);
      ExternalBookSearchResult nextResult = concurrentService.loadMore(userId, searchId);

      assertThat(firstResult.books())
          .extracting(item -> item.bookInfo())
          .containsExactlyElementsOf(pending.subList(0, 20));
      assertThat(nextResult.books())
          .extracting(item -> item.bookInfo())
          .containsExactlyElementsOf(pending.subList(20, 40));
      assertThat(stateStore.findById(searchId))
          .hasValueSatisfying(
              saved -> assertThat(saved.pendingBooks()).containsExactly(pending.get(40)));
      verifyNoInteractions(externalBookSearchPort);
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 조회나_외부_검색이_실패해도_다음_요청을_계속_거절하지_않는다(boolean failDuringRead) {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    BookSearchState state =
        new BookSearchState(userId, "자바", List.of(), Set.of(), Map.of(BookSearchField.TITLE, 1));
    BookException failure =
        new BookException(
            failDuringRead
                ? BookErrorCode.SEARCH_SESSION_STORE_UNAVAILABLE
                : BookErrorCode.EXTERNAL_BOOK_SEARCH_FAILED);
    ExternalBookFetchResult empty = new ExternalBookFetchResult(Map.of(), List.of());
    if (failDuringRead) {
      when(bookSearchStatePort.findById(searchId))
          .thenThrow(failure)
          .thenReturn(Optional.of(state));
      when(externalBookSearchPort.search("자바", state.nextPageByField())).thenReturn(empty);
    } else {
      when(bookSearchStatePort.findById(searchId)).thenReturn(Optional.of(state));
      when(externalBookSearchPort.search("자바", state.nextPageByField()))
          .thenThrow(failure)
          .thenReturn(empty);
    }

    assertThatThrownBy(() -> service.loadMore(userId, searchId)).isSameAs(failure);

    ExternalBookSearchResult retried = service.loadMore(userId, searchId);

    assertThat(retried.books()).isEmpty();
    assertThat(retried.hasNext()).isFalse();
    verify(bookSearchStatePort).delete(searchId);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 25})
  void 최초_검색은_전체_필드의_1페이지부터_조회하고_등록_정보와_남은_도서를_처리한다(int bookCount) {
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    Map<BookSearchField, Integer> initialPages =
        Map.of(BookSearchField.TITLE, 1, BookSearchField.AUTHOR, 1, BookSearchField.PUBLISHER, 1);
    List<BookSearchMeta> fetched =
        IntStream.range(0, bookCount)
            .mapToObj(i -> new BookSearchMeta("isbn-" + i, "도서 " + i, null, null, null, null))
            .toList();
    when(externalBookSearchPort.search("자바", initialPages))
        .thenReturn(new ExternalBookFetchResult(Map.of(), fetched));
    BookShelfInfo registeredShelf = new BookShelfInfo(shelfId, "내 서재");
    List<String> selectedIsbns = fetched.stream().limit(20).map(BookSearchMeta::isbn).toList();
    if (bookCount > 0) {
      when(bookRegistrationLookupPort.lookup(userId, selectedIsbns))
          .thenReturn(Map.of("isbn-0", List.of(registeredShelf)));
    }

    ExternalBookSearchResult result = service.search(userId, "자바");

    assertThat(result.searchId()).isNotNull();
    assertThat(result.books())
        .extracting(item -> item.bookInfo())
        .containsExactlyElementsOf(fetched.subList(0, Math.min(20, bookCount)));
    verify(externalBookSearchPort).search("자바", initialPages);
    verifyNoMoreInteractions(externalBookSearchPort);
    if (bookCount == 0) {
      assertThat(result.hasNext()).isFalse();
      verify(bookSearchStatePort).delete(result.searchId());
      verify(bookSearchStatePort, never()).save(any(), any());
      verifyNoInteractions(bookRegistrationLookupPort);
    } else {
      assertThat(result.hasNext()).isTrue();
      assertThat(result.books().get(0).registeredShelves()).containsExactly(registeredShelf);
      assertThat(result.books().subList(1, 20))
          .allSatisfy(item -> assertThat(item.registeredShelves()).isEmpty());
      ArgumentCaptor<BookSearchState> captor = ArgumentCaptor.forClass(BookSearchState.class);
      verify(bookSearchStatePort).save(eq(result.searchId()), captor.capture());
      BookSearchState saved = captor.getValue();
      assertThat(saved.userId()).isEqualTo(userId);
      assertThat(saved.keyword()).isEqualTo("자바");
      assertThat(saved.pendingBooks()).containsExactlyElementsOf(fetched.subList(20, 25));
      assertThat(saved.nextPageByField()).isEmpty();
      assertThat(saved.seenIsbns())
          .containsExactlyInAnyOrderElementsOf(fetched.stream().map(BookSearchMeta::isbn).toList());
      verify(bookRegistrationLookupPort).lookup(userId, selectedIsbns);
      verify(bookSearchStatePort, never()).delete(any());
    }
  }

  @Test
  void 반환한_도서만_캐싱하고_다음_페이지_버퍼의_도서는_아직_캐싱하지_않는다() {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    List<BookSearchMeta> pending =
        IntStream.range(0, 25)
            .mapToObj(i -> new BookSearchMeta("isbn-" + i, "제목", null, null, null, null))
            .toList();
    when(bookSearchStatePort.findById(searchId))
        .thenReturn(Optional.of(new BookSearchState(userId, "자바", pending, Set.of(), Map.of())));
    when(bookRegistrationLookupPort.lookup(eq(userId), any())).thenReturn(Map.of());

    ExternalBookSearchResult result = service.loadMore(userId, searchId);

    assertThat(result.books()).hasSize(20);
    assertThat(result.hasNext()).isTrue();
    ArgumentCaptor<BookSearchMeta> captor = ArgumentCaptor.forClass(BookSearchMeta.class);
    verify(bookMetadataCachePort, times(20)).save(captor.capture());
    assertThat(captor.getAllValues()).containsExactlyElementsOf(pending.subList(0, 20));
    verifyNoMoreInteractions(bookMetadataCachePort);
  }

  @Test
  void 마지막_페이지는_세션을_삭제한_뒤에도_반환_도서를_캐싱한다() {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    BookSearchMeta book = new BookSearchMeta("isbn", "제목", null, null, null, null);
    when(bookSearchStatePort.findById(searchId))
        .thenReturn(
            Optional.of(
                new BookSearchState(userId, "자바", List.of(book), Set.of("isbn"), Map.of())));
    when(bookRegistrationLookupPort.lookup(userId, List.of("isbn"))).thenReturn(Map.of());

    ExternalBookSearchResult result = service.loadMore(userId, searchId);

    assertThat(result.hasNext()).isFalse();
    InOrder order = inOrder(bookSearchStatePort, bookMetadataCachePort);
    order.verify(bookSearchStatePort).delete(searchId);
    order.verify(bookMetadataCachePort).save(book);
  }

  @Test
  void 검색_세션_저장이_실패하면_캐시_저장도_진행하지_않는다() {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    List<BookSearchMeta> pending =
        IntStream.range(0, 21)
            .mapToObj(i -> new BookSearchMeta("isbn-" + i, "제목", null, null, null, null))
            .toList();
    when(bookSearchStatePort.findById(searchId))
        .thenReturn(Optional.of(new BookSearchState(userId, "자바", pending, Set.of(), Map.of())));
    when(bookRegistrationLookupPort.lookup(eq(userId), any())).thenReturn(Map.of());
    BookException failure = new BookException(BookErrorCode.SEARCH_SESSION_STORE_UNAVAILABLE);
    doThrow(failure).when(bookSearchStatePort).save(eq(searchId), any());

    assertThatThrownBy(() -> service.loadMore(userId, searchId)).isSameAs(failure);

    verifyNoInteractions(bookMetadataCachePort);
  }

  @Test
  void 캐시의_직렬화_오류는_무시하지_않는다() {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    BookSearchMeta book = new BookSearchMeta("isbn", "제목", null, null, null, null);
    when(bookSearchStatePort.findById(searchId))
        .thenReturn(
            Optional.of(
                new BookSearchState(userId, "자바", List.of(book), Set.of("isbn"), Map.of())));
    when(bookRegistrationLookupPort.lookup(userId, List.of("isbn"))).thenReturn(Map.of());
    SerializationException failure = new SerializationException("test invalid data");
    doThrow(failure).when(bookMetadataCachePort).save(book);

    assertThatThrownBy(() -> service.loadMore(userId, searchId)).isSameAs(failure);
  }
}
