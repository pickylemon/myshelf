package com.myshelf.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.auth.application.port.out.AccessTokenIssuerPort;
import com.myshelf.book.adapter.out.redis.RedisBookMetadataCache;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.port.out.search.ExternalBookSearchPort;
import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.user.adapter.out.persistence.UserPersistenceAdapter;
import com.myshelf.user.domain.User;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 JWT·등록 Service·Writer·PostgreSQL·Redis를 연결하고 외부 API만 Mock으로 대체한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "redis"})
class ShelfBookAddIntegrationTest {
  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AccessTokenIssuerPort tokenIssuer;
  @Autowired UserPersistenceAdapter userAdapter;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired DataRedisProperties redisProperties;
  @Autowired RedisBookMetadataCache metadataCache;

  @Autowired
  @Qualifier("bookMetadataRedisTemplate")
  RedisTemplate<String, BookSearchMeta> redisTemplate;

  @MockitoBean ExternalBookSearchPort externalBookSearchPort;

  private final List<UUID> userIds = new ArrayList<>();
  private final List<String> metadataKeys = new ArrayList<>();

  @BeforeEach
  void 테스트_DB와_Redis_DB를_사용하는지_확인한다() {
    assertThat(jdbcTemplate.queryForObject("select current_database()", String.class))
        .isEqualTo("myshelf_test");
    assertThat(redisProperties.getDatabase()).isEqualTo(1);
  }

  @AfterEach
  void cleanUp() {
    try {
      for (String key : metadataKeys) redisTemplate.delete(key);
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

  // TODO(직접 작성): 한 요청에서 앞 도서는 저장 가능하고 뒤 도서는 DB 제약 위반이 나도록 준비한다.
  // HTTP 성공이 아님을 확인하고, 앞 도서 생성·기존 도서의 상태 변경·ShelfBook 연결까지
  // 모두 롤백됐는지 JDBC로 검증한다. 테스트 메서드에 @Transactional을 붙이지 않는다.
  // 재사용 가능한 Redis 메타데이터가 남는 것은 PostgreSQL 롤백 실패가 아니다.

  @Test
  void 실제_JWT의_사용자에게_여러_도서를_등록하고_연결_ID를_응답한다() throws Exception {
    UUID userId = createUser();
    UUID shelfId = insertShelf(userId, "내 서재");
    BookSearchMeta first = cacheBook();
    BookSearchMeta second = cacheBook();
    UUID otherBookId = insertUserBook(createUser(), first.isbn(), "타인의 제목", ReadingStatus.READING);

    JsonNode response =
        addBooks(
            userId,
            shelfId,
            List.of(
                new BookInput(first.isbn(), ReadingStatus.READ),
                new BookInput(second.isbn(), ReadingStatus.WANT_TO_READ)));

    assertThat(response.get("books").size()).isEqualTo(2);
    for (int index = 0; index < 2; index++) {
      BookSearchMeta expected = List.of(first, second).get(index);
      JsonNode item = response.get("books").get(index);
      assertThat(item.get("isbn").asString()).isEqualTo(expected.isbn());
      UUID shelfBookId = UUID.fromString(item.get("shelfBookId").asString());
      Map<String, Object> stored =
          jdbcTemplate.queryForMap(
              """
              select sb.shelf_id, sb.user_book_id, sb.position, sb.created_at as linked_at,
                     ub.user_id, ub.isbn, ub.title, ub.author, ub.publisher, ub.cover_image_url,
                     ub.reading_status_code, sb.memo, ub.created_at, ub.updated_at
              from shelf_books sb join user_books ub on ub.id = sb.user_book_id
              where sb.id = ?
              """,
              shelfBookId);
      assertThat(stored.get("shelf_id")).isEqualTo(shelfId);
      assertThat(stored.get("user_id")).isEqualTo(userId);
      assertThat(stored.get("user_book_id")).isNotEqualTo(otherBookId);
      assertThat(stored.get("isbn")).isEqualTo(expected.isbn());
      assertThat(stored.get("title")).isEqualTo(expected.title());
      assertThat(stored.get("author")).isEqualTo(expected.author());
      assertThat(stored.get("publisher")).isEqualTo(expected.publisher());
      assertThat(stored.get("cover_image_url")).isEqualTo(expected.coverImageUrl());
      assertThat(stored.get("reading_status_code")).isEqualTo(index == 0 ? "READ" : "WANT_TO_READ");
      assertThat(stored.get("memo")).isNull();
      assertThat(stored.get("position")).isNull();
      assertThat(stored.get("linked_at")).isNotNull();
      assertThat(stored.get("created_at")).isNotNull();
      assertThat(stored.get("updated_at")).isNotNull();
    }
    assertThat(
            jdbcTemplate.queryForMap(
                "select title, reading_status_code from user_books where id = ?", otherBookId))
        .containsEntry("title", "타인의 제목")
        .containsEntry("reading_status_code", "READING");
    verifyNoInteractions(externalBookSearchPort);
  }

  @Test
  void 다른_서재의_메모는_유지하고_새_연결은_메모_없이_선택한_상태로_등록한다() throws Exception {
    UUID userId = createUser();
    UUID oldShelfId = insertShelf(userId, "기존 서재");
    UUID newShelfId = insertShelf(userId, "새 서재");
    String isbn = newIsbn();
    UUID bookId = insertUserBook(userId, isbn, "기존 제목", ReadingStatus.READING);
    UUID oldLinkId = insertShelfBook(oldShelfId, bookId, "보존할 메모");
    Map<String, Object> oldLinkBefore =
        jdbcTemplate.queryForMap("select * from shelf_books where id = ?", oldLinkId);

    JsonNode response =
        addBooks(userId, newShelfId, List.of(new BookInput(isbn, ReadingStatus.READ)));

    UUID newLinkId = UUID.fromString(response.get("books").get(0).get("shelfBookId").asString());
    assertThat(
            jdbcTemplate.queryForMap(
                "select shelf_id, user_book_id, memo from shelf_books where id = ?", newLinkId))
        .containsEntry("shelf_id", newShelfId)
        .containsEntry("user_book_id", bookId)
        .containsEntry("memo", null);
    assertThat(jdbcTemplate.queryForMap("select * from shelf_books where id = ?", oldLinkId))
        .isEqualTo(oldLinkBefore);
    assertThat(
            jdbcTemplate.queryForMap(
                "select title, reading_status_code from user_books where id = ?", bookId))
        .containsEntry("title", "기존 제목")
        .containsEntry("reading_status_code", "READ");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from user_books where user_id = ? and isbn = ?",
                Long.class,
                userId,
                isbn))
        .isEqualTo(1L);
    verifyNoInteractions(externalBookSearchPort);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 대상_서재의_기존_연결은_변경하지_않고_새_연결만_응답한다(boolean includeNewBook) throws Exception {
    UUID userId = createUser();
    UUID shelfId = insertShelf(userId, "내 서재");
    String existingIsbn = newIsbn();
    UUID bookId = insertUserBook(userId, existingIsbn, "기존 제목", ReadingStatus.READING);
    UUID linkId = insertShelfBook(shelfId, bookId, "기존 메모");
    Map<String, Object> linkBefore =
        jdbcTemplate.queryForMap("select * from shelf_books where id = ?", linkId);
    Map<String, Object> before =
        jdbcTemplate.queryForMap("select * from user_books where id = ?", bookId);
    List<BookInput> inputs = new ArrayList<>();
    inputs.add(new BookInput(existingIsbn, ReadingStatus.READ));
    if (includeNewBook) inputs.add(new BookInput(cacheBook().isbn(), ReadingStatus.WANT_TO_READ));

    JsonNode response = addBooks(userId, shelfId, inputs);

    assertThat(response.get("books").size()).isEqualTo(includeNewBook ? 1 : 0);
    if (includeNewBook)
      assertThat(response.get("books").get(0).get("isbn").asString())
          .isEqualTo(inputs.get(1).isbn());
    assertThat(jdbcTemplate.queryForMap("select * from user_books where id = ?", bookId))
        .isEqualTo(before);
    assertThat(jdbcTemplate.queryForMap("select * from shelf_books where id = ?", linkId))
        .isEqualTo(linkBefore);
    verifyNoInteractions(externalBookSearchPort);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 타인_서재와_없는_서재는_등록_전에_404로_거부한다(boolean otherOwner) throws Exception {
    UUID userId = createUser();
    UUID shelfId = otherOwner ? insertShelf(createUser(), "타인 서재") : UUID.randomUUID();

    mockMvc
        .perform(
            post("/shelves/{shelfId}/books", shelfId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("books", List.of(new BookInput(newIsbn(), ReadingStatus.READ))))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SHELF_NOT_FOUND"));

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from user_books where user_id = ?", Long.class, userId))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from shelf_books where shelf_id = ?", Long.class, shelfId))
        .isZero();
    verifyNoInteractions(externalBookSearchPort);
  }

  private JsonNode addBooks(UUID userId, UUID shelfId, List<BookInput> books) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/shelves/{shelfId}/books", shelfId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("books", books))))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response);
  }

  private BookSearchMeta cacheBook() {
    String isbn = newIsbn();
    metadataKeys.add("myshelf:book-metadata" + isbn);
    BookSearchMeta book =
        new BookSearchMeta(isbn, "캐시의 제목", "저자", "출판사", LocalDate.of(2026, 10, 1), "cover");
    metadataCache.save(book);
    return book;
  }

  private String newIsbn() {
    return "978%010d".formatted(ThreadLocalRandom.current().nextLong(10_000_000_000L));
  }

  private UUID createUser() {
    UUID id = UUID.randomUUID();
    userIds.add(id);
    userAdapter.save(new User(id, "book-add-" + id + "@example.com", "encoded-password"));
    return id;
  }

  private UUID insertShelf(UUID userId, String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into shelves (id, user_id, name) values (?, ?, ?)", id, userId, name);
    return id;
  }

  private UUID insertUserBook(UUID userId, String isbn, String title, ReadingStatus status) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into user_books (id, user_id, isbn, title, reading_status_code) values (?, ?,"
            + " ?, ?, ?)",
        id,
        userId,
        isbn,
        title,
        status.name());
    return id;
  }

  private UUID insertShelfBook(UUID shelfId, UUID bookId, String memo) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into shelf_books (id, shelf_id, user_book_id, position, memo) values (?, ?, ?, null, ?)",
        id,
        shelfId,
        bookId,
        memo);
    return id;
  }

  private record BookInput(String isbn, ReadingStatus readingStatus) {}

  /** 연결 없는 UserBook은 같은 ID로 재사용하고 요청 상태·메모 없는 새 연결을 적용한다. */
  @Test
  void 모든_서재에서_제거했던_도서는_기존_ID로_재등록한다() throws Exception {
    UUID userId = createUser();
    UUID shelfId = insertShelf(userId, "재등록 서재");
    String isbn = newIsbn();
    UUID bookId = insertUserBook(userId, isbn, "기존 제목", ReadingStatus.READ);

    JsonNode response =
        addBooks(userId, shelfId, List.of(new BookInput(isbn, ReadingStatus.READING)));

    UUID linkId = UUID.fromString(response.get("books").get(0).get("shelfBookId").asString());
    assertThat(
            jdbcTemplate.queryForMap(
                "select user_book_id, memo from shelf_books where id = ?", linkId))
        .containsEntry("user_book_id", bookId)
        .containsEntry("memo", null);
    assertThat(
            jdbcTemplate.queryForMap(
                "select title, reading_status_code from user_books where id = ?", bookId))
        .containsEntry("title", "기존 제목")
        .containsEntry("reading_status_code", "READING");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from user_books where user_id = ? and isbn = ?",
                Long.class,
                userId,
                isbn))
        .isEqualTo(1L);
    verifyNoInteractions(externalBookSearchPort);
  }
}
