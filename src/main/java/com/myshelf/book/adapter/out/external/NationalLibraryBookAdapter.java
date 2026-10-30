package com.myshelf.book.adapter.out.external;

import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.dto.ExternalBookFetchResult;
import com.myshelf.book.application.port.out.BookSearchField;
import com.myshelf.book.application.port.out.ExternalBookSearchPort;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class NationalLibraryBookAdapter implements ExternalBookSearchPort {
  private final RestClient restClient;
  private final Executor searchExecutor;

  private final String BASE_URL = "https://www.nl.go.kr";
  private final String SEARCH_PATH = "/seoji/SearchApi.do";
  private static final int API_PAGE_SIZE = 100;

  private final String apiKey;

  public NationalLibraryBookAdapter(
      RestClient.Builder builder,
      @Value("${national-library.api-key}") String key,
      @Qualifier("searchExecutor") Executor searchExecutor) {
    this.restClient = builder.baseUrl(BASE_URL).build();

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

    futures.forEach((field, future) -> results.put(field, future.join()));

    return results;
  }

  private NationalLibrarySearchResponse fetchByField(String searchField, String keyword, int page) {
    return restClient
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
        response.publicationDate(),
        response.coverImageUrl());
  }

  private static String toApiSearchField(BookSearchField field) {
    return switch (field) {
      case TITLE -> "title";
      case AUTHOR -> "author";
      case PUBLISHER -> "publisher";
    };
  }
}
