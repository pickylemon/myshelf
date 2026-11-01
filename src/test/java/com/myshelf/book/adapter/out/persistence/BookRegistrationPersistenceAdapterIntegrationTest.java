package com.myshelf.book.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.myshelf.book.application.dto.BookShelfInfo;
import com.myshelf.user.adapter.out.persistence.UserPersistenceAdapter;
import com.myshelf.user.domain.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 실제 PostgreSQL의 조인 조건·사용자 격리·ISBN별 서재 매핑을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
class BookRegistrationPersistenceAdapterIntegrationTest {

  @Autowired BookRegistrationPersistenceAdapter adapter;
  @Autowired UserPersistenceAdapter userPersistenceAdapter;
  @Autowired JdbcTemplate jdbcTemplate;

  private final List<UUID> userIds = new ArrayList<>();

  @BeforeEach
  void 테스트_DB를_사용하는지_확인한다() {
    assertThat(jdbcTemplate.queryForObject("select current_database()", String.class))
        .isEqualTo("myshelf_test");
  }

  @AfterEach
  void cleanUp() {
    for (UUID userId : userIds) {
      jdbcTemplate.update(
          """
          delete from shelf_books
          where shelf_id in (select id from shelves where user_id = ?)
             or user_book_id in (select id from user_books where user_id = ?)
          """,
          userId,
          userId);
      jdbcTemplate.update("delete from user_books where user_id = ?", userId);
      jdbcTemplate.update("delete from shelves where user_id = ?", userId);
      jdbcTemplate.update("delete from users where id = ?", userId);
    }
  }

  @Test
  void 요청한_ISBN의_본인_도서와_본인_서재만_묶어서_반환한다() {
    UUID owner = createUser();
    UUID other = createUser();
    UUID firstShelf = insertShelf(owner, "첫 서재");
    UUID secondShelf = insertShelf(owner, "둘째 서재");
    UUID otherShelf = insertShelf(other, "타인 서재");
    String isbn = "9781234567890";
    UUID book = insertUserBook(owner, isbn);
    UUID otherBook = insertUserBook(other, isbn);
    insertShelfBook(firstShelf, book);
    insertShelfBook(secondShelf, book);
    insertShelfBook(otherShelf, otherBook);
    // 현재 FK는 두 소유자의 일치까지 강제하지 않으므로, 조인의 양쪽 사용자 조건을 확인한다.
    insertShelfBook(otherShelf, book);
    insertShelfBook(firstShelf, otherBook);
    insertShelfBook(firstShelf, insertUserBook(owner, "9781234567891"));
    insertUserBook(owner, "9781234567892"); // 도서는 등록했지만 연결된 서재는 없다.

    Map<String, List<BookShelfInfo>> result =
        adapter.lookup(owner, List.of(isbn, "9781234567892", "9781234567893"));

    assertThat(result).containsOnlyKeys(isbn);
    assertThat(result.get(isbn))
        .containsExactlyInAnyOrder(
            new BookShelfInfo(firstShelf, "첫 서재"), new BookShelfInfo(secondShelf, "둘째 서재"));
  }

  @Test
  void 타인에게만_등록된_도서는_조회되지_않는다() {
    UUID owner = createUser();
    UUID other = createUser();
    insertShelfBook(insertShelf(other, "타인 서재"), insertUserBook(other, "9781234567890"));

    assertThat(adapter.lookup(owner, List.of("9781234567890"))).isEmpty();
  }

  @Test
  void 조회할_ISBN이_없으면_등록_데이터가_있어도_빈_Map을_반환한다() {
    UUID owner = createUser();
    insertShelfBook(insertShelf(owner, "내 서재"), insertUserBook(owner, "9781234567890"));

    assertThat(adapter.lookup(owner, List.of())).isEmpty();
  }

  private UUID createUser() {
    UUID userId = UUID.randomUUID();
    userIds.add(userId);
    userPersistenceAdapter.save(
        new User(userId, "book-registration-" + userId + "@example.com", "encoded-password"));
    return userId;
  }

  private UUID insertShelf(UUID userId, String name) {
    UUID shelfId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into shelves (id, user_id, name) values (?, ?, ?)", shelfId, userId, name);
    return shelfId;
  }

  private UUID insertUserBook(UUID userId, String isbn) {
    UUID bookId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        insert into user_books (id, user_id, isbn, title, reading_status_code)
        values (?, ?, ?, ?, 'WANT_TO_READ')
        """,
        bookId,
        userId,
        isbn,
        "저장된 도서");
    return bookId;
  }

  private void insertShelfBook(UUID shelfId, UUID userBookId) {
    jdbcTemplate.update(
        "insert into shelf_books (id, shelf_id, user_book_id, position) values (?, ?, ?, ?)",
        UUID.randomUUID(),
        shelfId,
        userBookId,
        0);
  }
}
