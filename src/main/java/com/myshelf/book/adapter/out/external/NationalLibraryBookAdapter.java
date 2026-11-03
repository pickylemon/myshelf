package com.myshelf.book.adapter.out.external;

import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.dto.ExternalBookFetchResult;
import com.myshelf.book.application.port.out.search.BookSearchField;
import com.myshelf.book.application.port.out.search.ExternalBookSearchPort;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class NationalLibraryBookAdapter implements ExternalBookSearchPort {
  private final RestClient restClient;
  private final Executor searchExecutor;

  private final String SEARCH_PATH = "/seoji/SearchApi.do";
  private static final int API_PAGE_SIZE = 100;

  private final String apiKey;

  public NationalLibraryBookAdapter(
      @Qualifier("nationalLibraryRestClient") RestClient restClient,
      @Value("${national-library.api-key}") String key,
      @Qualifier("searchExecutor") Executor searchExecutor) {
    this.restClient = restClient;

    this.apiKey = key;

    this.searchExecutor = searchExecutor;
  }

  @Override
  public ExternalBookFetchResult search(
      String keyword, Map<BookSearchField, Integer> pageByFields) {
    // 1. 외부 도서 검색결과 반환
    Map<BookSearchField, NationalLibrarySearchResponse> results =
        fetchByFieldsConcurrently(keyword, pageByFields);

    // 2. field별 다음 page 여부 판단
    Map<BookSearchField, Integer> nextPageByFields = new LinkedHashMap<>();

    results.forEach(
        (field, response) -> {
          Integer nextPage = calculateNextPage(response.totalCount(), pageByFields.get(field));
          if (Objects.isNull(nextPage)) return;

          nextPageByFields.put(field, nextPage);
        });

    // 3. ISBN 기준으로 중복제거
    Set<String> seenIsbns = new HashSet<>();
    List<BookSearchMeta> bookResults =
        results.values().stream()
            .map(NationalLibrarySearchResponse::books)
            .flatMap(List::stream)
            .filter(Objects::nonNull)
            .filter(b -> StringUtils.hasText(b.isbn()) && seenIsbns.add(b.isbn()))
            .map(this::toResult)
            .toList();

    return new ExternalBookFetchResult(nextPageByFields, bookResults);
  }

  private Map<BookSearchField, NationalLibrarySearchResponse> fetchByFieldsConcurrently(
      String keyword, Map<BookSearchField, Integer> pageByFields) {
    Map<BookSearchField, CompletableFuture<NationalLibrarySearchResponse>> futures =
        new LinkedHashMap<>();
    // 1. 필드별 비동기 작업 제출
    pageByFields.forEach(
        (field, page) -> {
          futures.put(
              field,
              CompletableFuture.supplyAsync(
                  () -> fetchByField(toApiSearchField(field), keyword, page), searchExecutor));
        });

    // 2. 결과 수집
    Map<BookSearchField, NationalLibrarySearchResponse> results = new LinkedHashMap<>();

    try {
      futures.forEach((field, future) -> results.put(field, future.join()));
    } catch (CompletionException e) {
      if (e.getCause() instanceof BookException bookException) {
        throw bookException;
      }
      throw e;
    }

    return results;
  }

  private NationalLibrarySearchResponse fetchByField(String searchField, String keyword, int page) {
    try {
      NationalLibrarySearchResponse response =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(SEARCH_PATH)
                          .queryParam("cert_key", apiKey)
                          .queryParam("result_style", "json")
                          .queryParam("page_no", page)
                          .queryParam("page_size", API_PAGE_SIZE)
                          .queryParam(searchField, "{keyword}")
                          .build(keyword))
              .retrieve()
              .body(NationalLibrarySearchResponse.class);
      if (response == null || response.books() == null) {
        throw new BookException(BookErrorCode.INVALID_EXTERNAL_BOOK_RESPONSE);
      }
      return response;
    } catch (RestClientException e) {
      throw new BookException(classifyError(e), e);
    }
  }

  private BookErrorCode classifyError(RestClientException e) {
    if (isTimeout(e)) {
      return BookErrorCode.EXTERNAL_BOOK_SEARCH_TIMEOUT;
    }
    if (e instanceof RestClientResponseException || e instanceof ResourceAccessException) {
      return BookErrorCode.EXTERNAL_BOOK_SEARCH_FAILED;
    }
    return BookErrorCode.INVALID_EXTERNAL_BOOK_RESPONSE;
  }

  private boolean isTimeout(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
        return true;
      }
    }
    return false;
  }

  private Integer calculateNextPage(int totalCount, int currentPage) {
    return totalCount > currentPage * API_PAGE_SIZE ? currentPage + 1 : null;
  }

  private BookSearchMeta toResult(NationalLibraryBookResponse response) {
    return new BookSearchMeta(
        response.isbn(),
        response.title(),
        response.author(),
        response.publisher(),
        parseDate(response.publicationDate()),
        response.coverImageUrl());
  }

  private static String toApiSearchField(BookSearchField field) {
    return switch (field) {
      case TITLE -> "title";
      case AUTHOR -> "author";
      case PUBLISHER -> "publisher";
    };
  }

  private LocalDate parseDate(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }

    try {
      return LocalDate.parse(value.strip(), DateTimeFormatter.BASIC_ISO_DATE);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  @Override
  public Optional<BookSearchMeta> findByIsbn(String isbn) {
    NationalLibrarySearchResponse response = fetchByField("isbn", isbn, 1);

    return response.books().stream()
        .filter(Objects::nonNull)
        .filter(book -> isbn.equals(book.isbn()))
        .findFirst()
        .map(this::toResult);
  }
}
