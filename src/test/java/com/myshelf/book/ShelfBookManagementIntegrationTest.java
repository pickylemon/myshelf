package com.myshelf.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.auth.application.port.out.AccessTokenIssuerPort;
import com.myshelf.book.application.port.out.search.ExternalBookSearchPort;
import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.user.adapter.out.persistence.UserPersistenceAdapter;
import com.myshelf.user.domain.User;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/** 실제 JWT·PostgreSQL로 관리 API를 검증한다. 외부 API·Redis 호출은 필요하지 않다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShelfBookManagementIntegrationTest {
  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AccessTokenIssuerPort tokenIssuer;
  @Autowired UserPersistenceAdapter userAdapter;
  @Autowired JdbcTemplate jdbcTemplate;
  @MockitoBean ExternalBookSearchPort externalBookSearchPort;

  private static final Instant BEFORE = Instant.parse("2026-10-01T00:00:00Z");
  private final List<UUID> userIds = new ArrayList<>();

  // TODO(직접 작성): 마지막 ShelfBook을 API로 삭제해도 UserBook이 남는지 검증한다.

  @BeforeEach
  void 테스트_DB인지_확인한다() {
    assertThat(jdbcTemplate.queryForObject("select current_database()", String.class))
        .isEqualTo("myshelf_test");
  }

  @AfterEach
  void cleanUp() {
    for (UUID userId : userIds) {
      jdbcTemplate.update(
          "delete from shelf_books where shelf_id in (select id from shelves where user_id = ?)",
          userId);
      jdbcTemplate.update("delete from user_books where user_id = ?", userId);
      jdbcTemplate.update("delete from shelves where user_id = ?", userId);
      jdbcTemplate.update("delete from users where id = ?", userId);
    }
  }

  /** 메모만 변경하고 생성 시각·다른 연결·UserBook은 유지한다. 수정 시각은 DB 반영 후 확인한다. */
  @ParameterizedTest
  @MethodSource("memos")
  void 메모_변경은_해당_연결에만_반영하고_수정_시각을_갱신한다(String memo) throws Exception {
    UUID user = createUser();
    UUID book = insertBook(user, ReadingStatus.READING);
    UUID target = insertLink(insertShelf(user), book, "기존 메모");
    UUID other = insertLink(insertShelf(user), book, "다른 서재 메모");
    Map<String, Object> otherBefore = linkRow(other);
    Map<String, Object> bookBefore = bookRow(book);

    perform(
        user,
        patch("/shelf-books/{id}", target),
        objectMapper.writeValueAsString(new MemoBody(memo)));

    Map<String, Object> stored = linkRow(target);
    assertThat(stored.get("memo")).isEqualTo(memo);
    assertThat(((Timestamp) stored.get("created_at")).toInstant()).isEqualTo(BEFORE);
    assertThat(((Timestamp) stored.get("updated_at")).toInstant()).isAfter(BEFORE);
    assertThat(stored.get("user_book_id")).isEqualTo(book);
    assertThat(stored.get("position")).isNull();
    assertThat(linkRow(other)).isEqualTo(otherBefore);
    assertThat(bookRow(book)).isEqualTo(bookBefore);
    verifyNoInteractions(externalBookSearchPort);
  }

  static Stream<String> memos() {
    return Stream.of(null, "", "새 메모", "메".repeat(2000));
  }

  /** UserBook 상태 변경은 두 Shelf에서 공유되지만 각 연결의 메모·시각은 변경하지 않는다. */
  @ParameterizedTest
  @EnumSource(ReadingStatus.class)
  void 독서_상태는_여러_서재에서_공유하며_메모와_메타데이터를_보존한다(ReadingStatus status) throws Exception {
    UUID user = createUser();
    UUID book =
        insertBook(user, status == ReadingStatus.READ ? ReadingStatus.READING : ReadingStatus.READ);
    UUID first = insertLink(insertShelf(user), book, "첫 메모");
    UUID second = insertLink(insertShelf(user), book, "둘째 메모");
    Map<String, Object> firstBefore = linkRow(first);
    Map<String, Object> secondBefore = linkRow(second);
    Map<String, Object> before = bookRow(book);

    perform(
        user,
        patch("/user-books/{id}", book),
        objectMapper.writeValueAsString(Map.of("readingStatus", status)));

    Map<String, Object> after = bookRow(book);
    assertThat(after.get("reading_status_code")).isEqualTo(status.name());
    assertThat(((Timestamp) after.get("updated_at")).toInstant()).isAfter(BEFORE);
    before.remove("reading_status_code");
    before.remove("updated_at");
    after.remove("reading_status_code");
    after.remove("updated_at");
    assertThat(after).isEqualTo(before);
    assertThat(linkRow(first)).isEqualTo(firstBefore);
    assertThat(linkRow(second)).isEqualTo(secondBefore);
    assertThat(
            jdbcTemplate.queryForList(
                "select ub.reading_status_code from shelf_books sb join user_books ub on ub.id = sb.user_book_id where ub.id = ?",
                String.class,
                book))
        .containsExactlyInAnyOrder(status.name(), status.name());
  }

  /** 요청한 연결만 제거하며 아직 남은 다른 서재 연결과 UserBook 원본은 보존한다. */
  @Test
  void 다건_삭제는_선택한_연결만_삭제한다() throws Exception {
    UUID user = createUser();
    UUID book = insertBook(user, ReadingStatus.READ);
    UUID first = insertLink(insertShelf(user), book, "삭제 1");
    UUID second = insertLink(insertShelf(user), book, "삭제 2");
    UUID remaining = insertLink(insertShelf(user), book, "유지");
    Map<String, Object> remainingBefore = linkRow(remaining);
    Map<String, Object> bookBefore = bookRow(book);

    perform(
        user,
        delete("/shelf-books"),
        objectMapper.writeValueAsString(Map.of("shelfBookIds", List.of(first, second))));

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from shelf_books where id in (?, ?)", Long.class, first, second))
        .isZero();
    assertThat(linkRow(remaining)).isEqualTo(remainingBefore);
    assertThat(bookRow(book)).isEqualTo(bookBefore);
  }

  /** 유효한 ID와 미존재·타인 ID가 섞이면 앞의 유효한 연결까지 삭제하지 않는다. */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 일부_삭제_대상의_소유권을_확인하지_못하면_전체를_보존한다(boolean otherOwner) throws Exception {
    UUID user = createUser();
    UUID valid = insertLink(insertShelf(user), insertBook(user, ReadingStatus.READ), "유지할 메모");
    UUID otherUser = createUser();
    UUID invalid =
        otherOwner
            ? insertLink(
                insertShelf(otherUser), insertBook(otherUser, ReadingStatus.READING), "타인 메모")
            : UUID.randomUUID();
    List<Map<String, Object>> before =
        jdbcTemplate.queryForList("select * from shelf_books where id in (?, ?)", valid, invalid);

    mockMvc
        .perform(
            delete("/shelf-books")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("shelfBookIds", List.of(valid, invalid)))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SHELF_BOOK_NOT_FOUND"));

    assertThat(
            jdbcTemplate.queryForList(
                "select * from shelf_books where id in (?, ?)", valid, invalid))
        .containsExactlyInAnyOrderElementsOf(before);
  }

  /** 목록·메모·상태·삭제 모두 실제 JWT의 사용자 범위를 벗어나면 동일한 404 정책을 적용한다. */
  @ParameterizedTest
  @MethodSource("ownershipCases")
  void 타인_대상과_없는_대상은_404이며_원본을_보존한다(String operation, boolean otherOwner) throws Exception {
    UUID requester = createUser();
    UUID owner = createUser();
    UUID shelf = insertShelf(owner);
    UUID book = insertBook(owner, ReadingStatus.READING);
    UUID link = insertLink(shelf, book, "비공개 메모");
    Map<String, Object> beforeBook = bookRow(book);
    Map<String, Object> beforeLink = linkRow(link);
    UUID target =
        otherOwner
            ? switch (operation) {
              case "list" -> shelf;
              case "status" -> book;
              default -> link;
            }
            : UUID.randomUUID();
    MockHttpServletRequestBuilder request =
        switch (operation) {
          case "list" -> get("/shelves/{id}/books", target);
          case "memo" -> patch("/shelf-books/{id}", target).content("{\"memo\":\"변경\"}");
          case "status" ->
              patch("/user-books/{id}", target).content("{\"readingStatus\":\"READ\"}");
          default ->
              delete("/shelf-books")
                  .content(
                      objectMapper.writeValueAsString(Map.of("shelfBookIds", List.of(target))));
        };
    String code =
        operation.equals("list")
            ? "SHELF_NOT_FOUND"
            : operation.equals("status") ? "USER_BOOK_NOT_FOUND" : "SHELF_BOOK_NOT_FOUND";
    mockMvc
        .perform(
            request
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue(requester))
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(code));
    assertThat(bookRow(book)).isEqualTo(beforeBook);
    assertThat(linkRow(link)).isEqualTo(beforeLink);
  }

  static Stream<Arguments> ownershipCases() {
    return Stream.of("list", "memo", "status", "delete")
        .flatMap(
            operation -> Stream.of(Arguments.of(operation, true), Arguments.of(operation, false)));
  }

  /** DB에서 읽은 두 모델의 값을 목록 응답으로 전달한다. 정렬 검증은 별도 Adapter 실습에 남긴다. */
  @Test
  void 서재별_목록은_도서와_연결의_필드와_시각을_반환한다() throws Exception {
    UUID user = createUser();
    UUID shelf = insertShelf(user);
    UUID book = insertBook(user, ReadingStatus.READING);
    UUID link = insertLink(shelf, book, "목록 메모");
    UUID otherUser = createUser();
    insertLink(insertShelf(otherUser), insertBook(otherUser, ReadingStatus.READ), "타인 메모");

    mockMvc
        .perform(
            get("/shelves/{id}/books", shelf)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].shelfBookId").value(link.toString()))
        .andExpect(jsonPath("$[0].userBookId").value(book.toString()))
        .andExpect(jsonPath("$[0].isbn").value(bookRow(book).get("isbn")))
        .andExpect(jsonPath("$[0].title").value("제목"))
        .andExpect(jsonPath("$[0].author").value("저자"))
        .andExpect(jsonPath("$[0].publisher").value("출판사"))
        .andExpect(jsonPath("$[0].coverImageUrl").value("cover"))
        .andExpect(jsonPath("$[0].readingStatus").value("READING"))
        .andExpect(jsonPath("$[0].memo").value("목록 메모"))
        .andExpect(jsonPath("$[0].createdAt").value(BEFORE.toString()))
        .andExpect(jsonPath("$[0].updatedAt").value(BEFORE.toString()));
  }

  /** 서재는 존재하지만 연결이 없는 경우를 404와 구분한다. */
  @Test
  void 본인_소유_빈_서재는_200과_빈_배열이다() throws Exception {
    UUID user = createUser();
    mockMvc
        .perform(
            get("/shelves/{id}/books", insertShelf(user))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue(user)))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  /** Shelf 삭제의 DB CASCADE는 해당 연결만 지우고 다른 연결·메모·UserBook은 유지한다. */
  @Test
  void 서재를_삭제하면_해당_연결만_CASCADE로_제거한다() throws Exception {
    UUID user = createUser();
    UUID shelf = insertShelf(user);
    UUID book = insertBook(user, ReadingStatus.READ);
    UUID removed = insertLink(shelf, book, "삭제 메모");
    UUID remaining = insertLink(insertShelf(user), book, "다른 서재 메모");
    Map<String, Object> bookBefore = bookRow(book);
    Map<String, Object> linkBefore = linkRow(remaining);

    perform(user, delete("/shelves/{id}", shelf), null);

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from shelf_books where id = ?", Long.class, removed))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from shelves where id = ?", Long.class, shelf))
        .isZero();
    assertThat(bookRow(book)).isEqualTo(bookBefore);
    assertThat(linkRow(remaining)).isEqualTo(linkBefore);
  }

  private void perform(UUID userId, MockHttpServletRequestBuilder request, String body)
      throws Exception {
    request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue(userId));
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
    mockMvc.perform(request).andExpect(status().isNoContent()).andExpect(content().string(""));
  }

  private UUID createUser() {
    UUID id = UUID.randomUUID();
    userIds.add(id);
    userAdapter.save(new User(id, "book-manage-" + id + "@example.com", "encoded-password"));
    return id;
  }

  private UUID insertShelf(UUID userId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into shelves (id, user_id, name) values (?, ?, ?)", id, userId, "서재-" + id);
    return id;
  }

  private UUID insertBook(UUID userId, ReadingStatus status) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        insert into user_books (id, user_id, isbn, title, author, publisher, cover_image_url, reading_status_code, created_at, updated_at)
        values (?, ?, ?, '제목', '저자', '출판사', 'cover', ?, ?, ?)
        """,
        id,
        userId,
        "978" + id.toString().replace("-", "").substring(0, 10),
        status.name(),
        Timestamp.from(BEFORE),
        Timestamp.from(BEFORE));
    return id;
  }

  private UUID insertLink(UUID shelfId, UUID bookId, String memo) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into shelf_books (id, shelf_id, user_book_id, memo, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
        id,
        shelfId,
        bookId,
        memo,
        Timestamp.from(BEFORE),
        Timestamp.from(BEFORE));
    return id;
  }

  private Map<String, Object> bookRow(UUID id) {
    return jdbcTemplate.queryForMap("select * from user_books where id = ?", id);
  }

  private Map<String, Object> linkRow(UUID id) {
    return jdbcTemplate.queryForMap("select * from shelf_books where id = ?", id);
  }

  private record MemoBody(String memo) {}
}
