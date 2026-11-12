package com.myshelf.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.auth.application.port.out.AccessTokenIssuerPort;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.dto.ExternalBookFetchResult;
import com.myshelf.book.application.model.BookSearchState;
import com.myshelf.book.application.port.out.search.BookSearchField;
import com.myshelf.book.application.port.out.search.BookSearchStatePort;
import com.myshelf.book.application.port.out.search.ExternalBookSearchPort;
import com.myshelf.user.adapter.out.persistence.UserPersistenceAdapter;
import com.myshelf.user.domain.User;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 JWT·PostgreSQL·Redis를 연결하고 외부 도서 검색만 Mock으로 대체한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "redis"})
class BookSearchIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AccessTokenIssuerPort accessTokenIssuerPort;
  @Autowired UserPersistenceAdapter userPersistenceAdapter;
  @Autowired BookSearchStatePort stateStore;
  @Autowired DataRedisProperties redisProperties;
  @Autowired JdbcTemplate jdbcTemplate;

  @MockitoBean ExternalBookSearchPort externalBookSearchPort;

  private final List<UUID> userIds = new ArrayList<>();
  private final List<UUID> searchIds = new ArrayList<>();

  @BeforeEach
  void 테스트_DB와_Redis_DB를_사용하는지_확인한다() {
    assertThat(jdbcTemplate.queryForObject("select current_database()", String.class))
        .isEqualTo("myshelf_test");
    assertThat(redisProperties.getDatabase()).isEqualTo(1);
  }

  @AfterEach
  void cleanUp() {
    try {
      for (UUID searchId : searchIds) {
        stateStore.delete(searchId);
      }
    } finally {
      for (UUID userId : userIds) {
        jdbcTemplate.update(
            "delete from shelf_books where shelf_id in (select id from shelves where user_id = ?)",
            userId);
        jdbcTemplate.update("delete from user_books where user_id = ?", userId);
        jdbcTemplate.update("delete from shelves where user_id = ?", userId);
        jdbcTemplate.update("delete from users where id = ?", userId);
      }
    }
  }

  @Test
  void 최초_검색부터_버퍼_반환과_추가_조회_후_세션_종료까지_이어진다() throws Exception {
    UUID userId = createUser();
    List<BookSearchMeta> externalBooks = IntStream.range(0, 47).mapToObj(this::book).toList();
    UUID shelfId = insertRegistration(userId, externalBooks.get(0).isbn(), "내 서재");
    insertRegistration(createUser(), externalBooks.get(0).isbn(), "타인 서재");
    Map<BookSearchField, Integer> firstPages =
        Map.of(BookSearchField.TITLE, 1, BookSearchField.AUTHOR, 1, BookSearchField.PUBLISHER, 1);
    Map<BookSearchField, Integer> nextPages = Map.of(BookSearchField.TITLE, 2);
    when(externalBookSearchPort.search("자바", firstPages))
        .thenReturn(new ExternalBookFetchResult(nextPages, externalBooks.subList(0, 45)));
    when(externalBookSearchPort.search("자바", nextPages))
        .thenReturn(
            new ExternalBookFetchResult(
                Map.of(),
                List.of(externalBooks.get(0), externalBooks.get(45), externalBooks.get(46))));

    JsonNode first = readResponse(get("/books/search").param("keyword", "자바"), userId);
    UUID searchId = UUID.fromString(first.get("searchId").asString());
    searchIds.add(searchId);

    assertThat(responseIsbns(first))
        .containsExactlyElementsOf(
            externalBooks.subList(0, 20).stream().map(BookSearchMeta::isbn).toList());
    assertThat(first.get("hasNext").asBoolean()).isTrue();
    JsonNode firstBook = first.get("books").get(0);
    assertThat(firstBook.get("title").asString()).isEqualTo("도서 0");
    assertThat(firstBook.get("publicationDate").asString()).isEqualTo("2026-10-01");
    assertThat(firstBook.get("registeredShelves").size()).isEqualTo(1);
    assertThat(firstBook.get("registeredShelves").get(0).get("shelfId").asString())
        .isEqualTo(shelfId.toString());
    assertThat(firstBook.get("registeredShelves").get(0).get("shelfName").asString())
        .isEqualTo("내 서재");
    assertThat(first.get("books").get(1).get("registeredShelves").size()).isZero();
    assertThat(stateStore.findById(searchId))
        .hasValueSatisfying(
            saved -> {
              assertThat(saved.userId()).isEqualTo(userId);
              assertThat(saved.pendingBooks())
                  .containsExactlyElementsOf(externalBooks.subList(20, 45));
              assertThat(saved.nextPageByField()).containsExactlyEntriesOf(nextPages);
            });

    JsonNode second = readResponse(get("/books/search/{searchId}", searchId), userId);

    assertThat(responseIsbns(second))
        .containsExactlyElementsOf(
            externalBooks.subList(20, 40).stream().map(BookSearchMeta::isbn).toList());
    assertThat(second.get("searchId").asString()).isEqualTo(searchId.toString());
    assertThat(second.get("hasNext").asBoolean()).isTrue();
    verify(externalBookSearchPort, times(1)).search("자바", firstPages);
    verifyNoMoreInteractions(externalBookSearchPort);
    assertThat(stateStore.findById(searchId))
        .hasValueSatisfying(
            saved ->
                assertThat(saved.pendingBooks())
                    .containsExactlyElementsOf(externalBooks.subList(40, 45)));

    JsonNode last = readResponse(get("/books/search/{searchId}", searchId), userId);

    assertThat(responseIsbns(last))
        .containsExactlyElementsOf(
            externalBooks.subList(40, 47).stream().map(BookSearchMeta::isbn).toList());
    assertThat(last.get("hasNext").asBoolean()).isFalse();
    assertThat(last.get("searchId").asString()).isEqualTo(searchId.toString());
    assertThat(stateStore.findById(searchId)).isEmpty();
    verify(externalBookSearchPort).search("자바", nextPages);
    verifyNoMoreInteractions(externalBookSearchPort);

    mockMvc
        .perform(
            get("/books/search/{searchId}", searchId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(userId)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SEARCH_SESSION_NOT_FOUND"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 타인의_검색_세션과_없는_세션은_실제_JWT로_접근해도_404로_거부한다(boolean ownedByOtherUser) throws Exception {
    UUID userId = createUser();
    UUID searchId = UUID.randomUUID();
    BookSearchState otherState =
        new BookSearchState(
            UUID.randomUUID(), "비공개 검색어", List.of(book(0)), Set.of(book(0).isbn()), Map.of());
    if (ownedByOtherUser) {
      searchIds.add(searchId);
      stateStore.save(searchId, otherState);
    }

    mockMvc
        .perform(
            get("/books/search/{searchId}", searchId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(userId)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("SEARCH_SESSION_NOT_FOUND"));

    verifyNoInteractions(externalBookSearchPort);
    if (ownedByOtherUser) {
      assertThat(stateStore.findById(searchId)).contains(otherState);
    } else {
      assertThat(stateStore.findById(searchId)).isEmpty();
    }
  }

  private JsonNode readResponse(MockHttpServletRequestBuilder request, UUID userId)
      throws Exception {
    String response =
        mockMvc
            .perform(request.header(HttpHeaders.AUTHORIZATION, bearerToken(userId)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response);
  }

  private List<String> responseIsbns(JsonNode response) {
    JsonNode books = response.get("books");
    return IntStream.range(0, books.size())
        .mapToObj(i -> books.get(i).get("isbn").asString())
        .toList();
  }

  private String bearerToken(UUID userId) {
    return "Bearer " + accessTokenIssuerPort.issue(userId);
  }

  private UUID createUser() {
    UUID userId = UUID.randomUUID();
    userIds.add(userId);
    userPersistenceAdapter.save(
        new User(userId, "book-search-" + userId + "@example.com", "encoded-password"));
    return userId;
  }

  private BookSearchMeta book(int index) {
    return new BookSearchMeta(
        "978%010d".formatted(index),
        "도서 " + index,
        "저자",
        "출판사",
        LocalDate.of(2026, 10, 1),
        "https://example.com/cover/" + index);
  }

  private UUID insertRegistration(UUID userId, String isbn, String shelfName) {
    UUID shelfId = UUID.randomUUID();
    UUID userBookId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into shelves (id, user_id, name) values (?, ?, ?)", shelfId, userId, shelfName);
    jdbcTemplate.update(
        """
        insert into user_books (id, user_id, isbn, title, reading_status_code)
        values (?, ?, ?, ?, 'WANT_TO_READ')
        """,
        userBookId,
        userId,
        isbn,
        "저장된 도서");
    jdbcTemplate.update(
        "insert into shelf_books (id, shelf_id, user_book_id, position) values (?, ?, ?, ?)",
        UUID.randomUUID(),
        shelfId,
        userBookId,
        0);
    return shelfId;
  }
}
