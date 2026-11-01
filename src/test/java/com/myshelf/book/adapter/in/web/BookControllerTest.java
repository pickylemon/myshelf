package com.myshelf.book.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.auth.adapter.in.security.ProblemDetailAuthenticationEntryPoint;
import com.myshelf.book.application.dto.BookSearchItem;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.dto.BookShelfInfo;
import com.myshelf.book.application.dto.ExternalBookSearchResult;
import com.myshelf.book.application.port.in.ExternalBookSearchUseCase;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import com.myshelf.global.config.SecurityConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 검색 예외의 HTTP 응답을 검증한다. 세션 조회와 JWT 자체 검증은 각각 별도 테스트의 책임이다. */
@WebMvcTest(BookController.class)
@Import({SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class})
class BookControllerTest {

  @Autowired MockMvc mockMvc;

  @MockitoBean ExternalBookSearchUseCase externalBookSearchUseCase;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean AuthenticationManager authenticationManager;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 최초_검색과_더보기는_사용자_UUID를_전달하고_정상_응답을_매핑한다(boolean initialSearch) throws Exception {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    BookSearchMeta registered =
        new BookSearchMeta(
            "9781234567890",
            "자바",
            "저자",
            "출판사",
            LocalDate.of(2026, 10, 1),
            "https://example.com/cover");
    BookSearchMeta unregistered =
        new BookSearchMeta("9781234567891", "미등록 도서", null, null, null, null);
    ExternalBookSearchResult response =
        new ExternalBookSearchResult(
            List.of(
                new BookSearchItem(registered, List.of(new BookShelfInfo(shelfId, "내 서재"))),
                new BookSearchItem(unregistered, List.of())),
            true,
            searchId);
    MockHttpServletRequestBuilder request;
    if (initialSearch) {
      when(externalBookSearchUseCase.search(userId, "자바")).thenReturn(response);
      request = get("/books/search").param("keyword", "자바");
    } else {
      when(externalBookSearchUseCase.loadMore(userId, searchId)).thenReturn(response);
      request = get("/books/search/{searchId}", searchId);
    }

    mockMvc
        .perform(request.with(jwt().jwt(token -> token.subject(userId.toString()))))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.searchId").value(searchId.toString()))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.books.length()").value(2))
        .andExpect(jsonPath("$.books[0].isbn").value(registered.isbn()))
        .andExpect(jsonPath("$.books[0].title").value("자바"))
        .andExpect(jsonPath("$.books[0].author").value("저자"))
        .andExpect(jsonPath("$.books[0].publisher").value("출판사"))
        .andExpect(jsonPath("$.books[0].publicationDate").value("2026-10-01"))
        .andExpect(jsonPath("$.books[0].coverImageUrl").value(registered.coverImageUrl()))
        .andExpect(jsonPath("$.books[0].registeredShelves[0].shelfId").value(shelfId.toString()))
        .andExpect(jsonPath("$.books[0].registeredShelves[0].shelfName").value("내 서재"))
        .andExpect(jsonPath("$.books[1].registeredShelves").isArray())
        .andExpect(jsonPath("$.books[1].registeredShelves").isEmpty());

    if (initialSearch) {
      verify(externalBookSearchUseCase).search(userId, "자바");
    } else {
      verify(externalBookSearchUseCase).loadMore(userId, searchId);
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n"})
  void 검색어가_없거나_공백이면_UseCase_호출_전에_400으로_거부한다(String keyword) throws Exception {
    MockHttpServletRequestBuilder request = get("/books/search");
    if (keyword != null) {
      request.param("keyword", keyword);
    }

    mockMvc
        .perform(request.with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(externalBookSearchUseCase);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/books/search", "/books/search/00000000-0000-0000-0000-000000000001"})
  void 토큰이_없으면_검색_엔드포인트는_401로_거부한다(String path) throws Exception {
    mockMvc
        .perform(get(path).param("keyword", "자바"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));

    verifyNoInteractions(externalBookSearchUseCase);
  }

  @Test
  void 검색_ID가_UUID가_아니면_UseCase_호출_전에_400으로_거부한다() throws Exception {
    mockMvc
        .perform(
            get("/books/search/not-a-uuid")
                .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(externalBookSearchUseCase);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 빈_검색_결과도_200과_빈_배열로_반환하고_hasNext를_유지한다(boolean hasNext) throws Exception {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    when(externalBookSearchUseCase.loadMore(userId, searchId))
        .thenReturn(new ExternalBookSearchResult(List.of(), hasNext, searchId));

    mockMvc
        .perform(
            get("/books/search/{searchId}", searchId)
                .with(jwt().jwt(token -> token.subject(userId.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.books").isArray())
        .andExpect(jsonPath("$.books").isEmpty())
        .andExpect(jsonPath("$.hasNext").value(hasNext))
        .andExpect(jsonPath("$.searchId").value(searchId.toString()));
  }

  /** 세션 부재 예외는 인증 오류가 아닌 404와 프로젝트의 ProblemDetail 형식으로 응답한다. */
  @Test
  void 검색_세션_부재_예외를_404_ProblemDetail로_반환한다() throws Exception {
    // given
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    when(externalBookSearchUseCase.loadMore(userId, searchId))
        .thenThrow(new BookException(BookErrorCode.SEARCH_SESSION_NOT_FOUND));

    // when, then
    mockMvc
        .perform(
            get("/books/search/{searchId}", searchId)
                .with(jwt().jwt(token -> token.subject(userId.toString()))))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Book Error"))
        .andExpect(jsonPath("$.code").value("SEARCH_SESSION_NOT_FOUND"))
        .andExpect(jsonPath("$.detail").value(BookErrorCode.SEARCH_SESSION_NOT_FOUND.getMessage()))
        .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));

    verify(externalBookSearchUseCase).loadMore(userId, searchId);
  }

  @ParameterizedTest
  @MethodSource("externalSearchErrors")
  void 외부_검색_오류를_HTTP_응답으로_변환하고_내부_원인을_노출하지_않는다(BookErrorCode errorCode, HttpStatus expectedStatus)
      throws Exception {
    UUID userId = UUID.randomUUID();
    when(externalBookSearchUseCase.search(userId, "java"))
        .thenThrow(
            new BookException(errorCode, new IllegalStateException("upstream-secret-detail")));

    mockMvc
        .perform(
            get("/books/search")
                .param("keyword", "java")
                .with(jwt().jwt(token -> token.subject(userId.toString()))))
        .andExpect(status().is(expectedStatus.value()))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(expectedStatus.value()))
        .andExpect(jsonPath("$.title").value("Book Error"))
        .andExpect(jsonPath("$.code").value(errorCode.getCode()))
        .andExpect(jsonPath("$.detail").value(errorCode.getMessage()))
        .andExpect(content().string(not(containsString("upstream-secret-detail"))))
        .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));

    verify(externalBookSearchUseCase).search(userId, "java");
  }

  static Stream<Arguments> externalSearchErrors() {
    return Stream.of(
        Arguments.of(BookErrorCode.EXTERNAL_BOOK_SEARCH_FAILED, HttpStatus.BAD_GATEWAY),
        Arguments.of(BookErrorCode.INVALID_EXTERNAL_BOOK_RESPONSE, HttpStatus.BAD_GATEWAY),
        Arguments.of(BookErrorCode.EXTERNAL_BOOK_SEARCH_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT));
  }

  @Test
  void 검색_저장소_장애는_세션_부재와_구분하여_503으로_반환한다() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    when(externalBookSearchUseCase.loadMore(userId, searchId))
        .thenThrow(
            new BookException(
                BookErrorCode.SEARCH_SESSION_STORE_UNAVAILABLE,
                new IllegalStateException("redis-internal-detail")));

    mockMvc
        .perform(
            get("/books/search/{searchId}", searchId)
                .with(jwt().jwt(token -> token.subject(userId.toString()))))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(503))
        .andExpect(jsonPath("$.code").value("SEARCH_SESSION_STORE_UNAVAILABLE"))
        .andExpect(
            jsonPath("$.detail").value(BookErrorCode.SEARCH_SESSION_STORE_UNAVAILABLE.getMessage()))
        .andExpect(content().string(not(containsString("redis-internal-detail"))));

    verify(externalBookSearchUseCase).loadMore(userId, searchId);
  }

  @Test
  void 처리_중인_검색의_추가_요청은_409로_반환한다() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID searchId = UUID.randomUUID();
    when(externalBookSearchUseCase.loadMore(userId, searchId))
        .thenThrow(new BookException(BookErrorCode.SEARCH_REQUEST_IN_PROGRESS));

    mockMvc
        .perform(
            get("/books/search/{searchId}", searchId)
                .with(jwt().jwt(token -> token.subject(userId.toString()))))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("SEARCH_REQUEST_IN_PROGRESS"))
        .andExpect(
            jsonPath("$.detail").value(BookErrorCode.SEARCH_REQUEST_IN_PROGRESS.getMessage()));

    verify(externalBookSearchUseCase).loadMore(userId, searchId);
  }
}
