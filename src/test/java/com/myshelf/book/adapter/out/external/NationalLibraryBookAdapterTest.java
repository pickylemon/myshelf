package com.myshelf.book.adapter.out.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.myshelf.book.application.dto.ExternalBookFetchResult;
import com.myshelf.book.application.port.out.search.BookSearchField;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 실제 HTTP 통신 없이 외부 응답과 통신 오류의 변환을 검증한다. */
class NationalLibraryBookAdapterTest {

  // TODO(테스트 구현예졍): findByIsbn의 정상 조회. 부분 일치 항목보다 뒤에 정확히 일치하는 항목을 두고,
  // 요청의 isbn·page_no=1과 반환된 도서의 모든 메타데이터를 검증한다.

  private MockRestServiceServer server;
  private NationalLibraryBookAdapter adapter;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://www.nl.go.kr");
    server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
    // 비동기 결과의 예외 래핑은 유지하되 실행 순서를 일정하게 만든다.
    adapter = new NationalLibraryBookAdapter(builder.build(), "test-api-key", Runnable::run);
  }

  @AfterEach
  void tearDown() {
    server.verify();
  }

  @Test
  void 정상_응답의_도서와_날짜를_변환한다() {
    server
        .expect(queryParam("title", "java"))
        .andExpect(queryParam("cert_key", "test-api-key"))
        .andExpect(queryParam("page_no", "1"))
        .andExpect(queryParam("page_size", "100"))
        .andRespond(
            withSuccess(
                """
                {"TOTAL_COUNT":"1","docs":[{
                  "EA_ISBN":"9781234567890","TITLE":"자바",
                  "AUTHOR":"저자","PUBLISHER":"출판사","PUBLISH_PREDATE":"20261001"
                }]}
                """,
                MediaType.APPLICATION_JSON));

    ExternalBookFetchResult result = adapter.search("java", Map.of(BookSearchField.TITLE, 1));

    assertThat(result.books())
        .singleElement()
        .satisfies(
            book -> {
              assertThat(book.isbn()).isEqualTo("9781234567890");
              assertThat(book.publicationDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            });
    assertThat(result.nextPageByField()).isEmpty();
  }

  @Test
  void 검색_결과가_없는_정상_응답은_빈_목록으로_반환한다() {
    server
        .expect(queryParam("title", "java"))
        .andRespond(withSuccess("{\"TOTAL_COUNT\":0,\"docs\":[]}", MediaType.APPLICATION_JSON));

    ExternalBookFetchResult result = adapter.search("java", Map.of(BookSearchField.TITLE, 1));

    assertThat(result.books()).isEmpty();
    assertThat(result.nextPageByField()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 429, 500})
  void 외부_HTTP_오류를_검색_실패로_변환한다(int status) {
    server.expect(queryParam("title", "java")).andRespond(withStatus(HttpStatus.valueOf(status)));

    assertThatThrownBy(() -> adapter.search("java", Map.of(BookSearchField.TITLE, 1)))
        .isInstanceOfSatisfying(
            BookException.class,
            ex ->
                assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.EXTERNAL_BOOK_SEARCH_FAILED))
        .hasCauseInstanceOf(RestClientResponseException.class);
  }

  @Test
  void 연결_실패를_검색_실패로_변환한다() {
    server
        .expect(queryParam("title", "java"))
        .andRespond(withException(new ConnectException("test connection failure")));

    assertThatThrownBy(() -> adapter.search("java", Map.of(BookSearchField.TITLE, 1)))
        .isInstanceOfSatisfying(
            BookException.class,
            ex ->
                assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.EXTERNAL_BOOK_SEARCH_FAILED))
        .hasCauseInstanceOf(ResourceAccessException.class);
  }

  @ParameterizedTest
  @MethodSource("timeoutExceptions")
  void 시간_초과를_별도_예외로_변환한다(IOException timeout) {
    server.expect(queryParam("title", "java")).andRespond(withException(timeout));

    assertThatThrownBy(() -> adapter.search("java", Map.of(BookSearchField.TITLE, 1)))
        .isInstanceOfSatisfying(
            BookException.class,
            ex ->
                assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.EXTERNAL_BOOK_SEARCH_TIMEOUT))
        .hasCauseInstanceOf(ResourceAccessException.class);
  }

  static Stream<IOException> timeoutExceptions() {
    return Stream.of(
        new HttpTimeoutException("test timeout"), new SocketTimeoutException("test timeout"));
  }

  @ParameterizedTest
  @MethodSource("timeoutExceptions")
  void 응답_본문을_읽다가_발생한_시간_초과도_타임아웃으로_분류한다(IOException timeout) {
    server
        .expect(queryParam("title", "java"))
        .andRespond(
            request -> {
              InputStream body =
                  new InputStream() {
                    private boolean started;

                    @Override
                    public int read() throws IOException {
                      if (!started) {
                        started = true;
                        return '{';
                      }
                      throw timeout;
                    }
                  };
              MockClientHttpResponse response = new MockClientHttpResponse(body, HttpStatus.OK);
              response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
              return response;
            });

    assertThatThrownBy(() -> adapter.search("java", Map.of(BookSearchField.TITLE, 1)))
        .isInstanceOfSatisfying(
            BookException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.EXTERNAL_BOOK_SEARCH_TIMEOUT);
              assertThat(ex.getCause()).isExactlyInstanceOf(RestClientException.class);
            })
        .hasRootCauseInstanceOf(timeout.getClass());
  }

  @Test
  void 목록_안의_null_항목을_제외하고_정상_도서를_반환한다() {
    server
        .expect(queryParam("title", "java"))
        .andRespond(
            withSuccess(
                """
                {"TOTAL_COUNT":2,"docs":[null,{
                  "EA_ISBN":"9781234567890","TITLE":"자바"
                }]}
                """,
                MediaType.APPLICATION_JSON));

    ExternalBookFetchResult result = adapter.search("java", Map.of(BookSearchField.TITLE, 1));

    assertThat(result.books())
        .singleElement()
        .satisfies(
            book -> {
              assertThat(book.isbn()).isEqualTo("9781234567890");
              assertThat(book.title()).isEqualTo("자바");
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-json", "{\"TOTAL_COUNT\":1}"})
  void 본문_누락이나_JSON_변환_실패를_잘못된_응답으로_처리한다(String body) {
    server
        .expect(queryParam("title", "java"))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> adapter.search("java", Map.of(BookSearchField.TITLE, 1)))
        .isInstanceOfSatisfying(
            BookException.class,
            ex ->
                assertThat(ex.getErrorCode())
                    .isEqualTo(BookErrorCode.INVALID_EXTERNAL_BOOK_RESPONSE));
  }

  @Test
  void 일부_검색_필드가_실패하면_부분_결과를_반환하지_않는다() {
    server
        .expect(queryParam("title", "java"))
        .andRespond(withSuccess("{\"TOTAL_COUNT\":0,\"docs\":[]}", MediaType.APPLICATION_JSON));
    server
        .expect(queryParam("author", "java"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    assertThatThrownBy(
            () ->
                adapter.search("java", Map.of(BookSearchField.TITLE, 1, BookSearchField.AUTHOR, 1)))
        .isInstanceOfSatisfying(
            BookException.class,
            ex ->
                assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.EXTERNAL_BOOK_SEARCH_FAILED));
  }

  @ParameterizedTest
  @MethodSource("publicationDates")
  void 외부_날짜의_누락과_잘못된_값은_null로_정상_날짜는_LocalDate로_변환한다(String dateField, LocalDate expected) {
    server
        .expect(queryParam("title", "java"))
        .andRespond(
            withSuccess(
                """
                {"TOTAL_COUNT":1,"docs":[{%s"EA_ISBN":"9781234567890","TITLE":"자바"}]}
                """
                    .formatted(dateField),
                MediaType.APPLICATION_JSON));

    ExternalBookFetchResult result = adapter.search("java", Map.of(BookSearchField.TITLE, 1));

    assertThat(result.books())
        .singleElement()
        .satisfies(book -> assertThat(book.publicationDate()).isEqualTo(expected));
  }

  static Stream<Arguments> publicationDates() {
    return Stream.of(
        Arguments.of("", null),
        Arguments.of("\"PUBLISH_PREDATE\":null,", null),
        Arguments.of("\"PUBLISH_PREDATE\":\"\",", null),
        Arguments.of("\"PUBLISH_PREDATE\":\"   \",", null),
        Arguments.of("\"PUBLISH_PREDATE\":\"20260230\",", null),
        Arguments.of("\"PUBLISH_PREDATE\":\"invalid\",", null),
        Arguments.of("\"PUBLISH_PREDATE\":\" 20261001 \",", LocalDate.of(2026, 10, 1)));
  }

  @Test
  void 필드별_결과의_중복_ISBN과_ISBN이_없는_항목을_제외하고_다음_페이지를_계산한다() {
    server
        .expect(queryParam("title", "java"))
        .andRespond(
            withSuccess(
                """
                {"TOTAL_COUNT":101,"docs":[
                  {"EA_ISBN":"9781234567890","TITLE":"자바"},
                  {"EA_ISBN":"9781234567890","TITLE":"중복"},
                  {"TITLE":"ISBN 누락"},{"EA_ISBN":""},{"EA_ISBN":"   "}
                ]}
                """,
                MediaType.APPLICATION_JSON));
    server
        .expect(queryParam("author", "java"))
        .andRespond(
            withSuccess(
                """
                {"TOTAL_COUNT":2,"docs":[
                  {"EA_ISBN":"9781234567890","TITLE":"다른 필드의 중복"},
                  {"EA_ISBN":"9781234567891","TITLE":"다른 도서"}
                ]}
                """,
                MediaType.APPLICATION_JSON));

    ExternalBookFetchResult result =
        adapter.search("java", Map.of(BookSearchField.TITLE, 1, BookSearchField.AUTHOR, 1));

    assertThat(result.books())
        .extracting(book -> book.isbn())
        .containsExactlyInAnyOrder("9781234567890", "9781234567891");
    assertThat(result.nextPageByField()).containsExactlyEntriesOf(Map.of(BookSearchField.TITLE, 2));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"[]", "[null]", "[{\"EA_ISBN\":\"requested-extra\",\"TITLE\":\"부분 일치\"}]"})
  void ISBN이_정확히_일치하지_않으면_한_페이지_조회_후_빈_결과를_반환한다(String docs) {
    server
        .expect(queryParam("isbn", "requested"))
        .andExpect(queryParam("page_no", "1"))
        .andRespond(
            withSuccess(
                "{\"TOTAL_COUNT\":101,\"docs\":%s}".formatted(docs), MediaType.APPLICATION_JSON));

    assertThat(adapter.findByIsbn("requested")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 500})
  void ISBN_단건_외부_조회도_HTTP_실패를_BookException으로_변환한다(int status) {
    server
        .expect(queryParam("isbn", "requested"))
        .andRespond(withStatus(HttpStatus.valueOf(status)));

    assertThatThrownBy(() -> adapter.findByIsbn("requested"))
        .isInstanceOfSatisfying(
            BookException.class,
            ex ->
                assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.EXTERNAL_BOOK_SEARCH_FAILED));
  }
}
