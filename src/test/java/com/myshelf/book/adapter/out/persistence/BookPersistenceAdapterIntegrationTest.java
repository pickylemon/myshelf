package com.myshelf.book.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myshelf.book.application.dto.BookShelfInfo;
import com.myshelf.book.application.dto.ShelfBookItem;
import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.domain.ShelfBook;
import com.myshelf.book.domain.UserBook;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import com.myshelf.user.adapter.out.persistence.UserPersistenceAdapter;
import com.myshelf.user.domain.User;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 PostgreSQL의 조인 조건·사용자 격리·ISBN별 서재 매핑을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
class BookPersistenceAdapterIntegrationTest {

  @Autowired BookPersistenceAdapter adapter;
  @Autowired UserPersistenceAdapter userPersistenceAdapter;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired PlatformTransactionManager transactionManager;

  private final List<UUID> userIds = new ArrayList<>();

  @Test
  void 목록은_위치_지정_우선으로_위치_수정시각_ID_순서로_정렬한다() {
    UUID owner = createUser();
    UUID shelf = insertShelf(owner, "정렬 서재");
    Instant older = Instant.parse("2026-10-01T00:00:00Z");
    Instant newer = older.plusSeconds(60);
    Integer[] positions = {null, 2, null, 1, null, 2};
    Instant[] updatedTimes = {older, newer, older, older, newer, older};
    List<UUID> ids = new ArrayList<>();

    // 모든 정렬 조건의 우선순위를 구분할 수 있도록 삽입 순서와 기대 순서를 다르게 둔다.
    for (int index = 0; index < positions.length; index++) {
      UUID id = new UUID(0, index + 1);
      ids.add(id);
      UUID book = insertUserBook(owner, "978123456789" + index);
      jdbcTemplate.update(
          "insert into shelf_books (id, shelf_id, user_book_id, position, created_at, updated_at)"
              + " values (?, ?, ?, ?, ?, ?)",
          id,
          shelf,
          book,
          positions[index],
          Timestamp.from(older),
          Timestamp.from(updatedTimes[index]));
    }

    assertThat(adapter.findAllShelfBooksByShelfIdAndUserId(shelf, owner))
        .extracting(ShelfBookItem::shelfBookId)
        .containsExactly(ids.get(3), ids.get(1), ids.get(5), ids.get(4), ids.get(2), ids.get(0));
  }

  /** 트랜잭션 내 메모 변경이 commit되고 연결 ID·생성 시각은 유지된다. */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "새 메모"})
  void 메모_변경은_생성_시각과_연결_정보를_보존한다(String memo) {
    UUID owner = createUser();
    UUID shelf = insertShelf(owner, "메모 서재");
    UUID book = insertUserBook(owner, "9781234567890");
    // null·빈 문자열 요청도 실제 변경이 되도록 기존 메모를 준비한다.
    ShelfBook saved =
        adapter.createShelfBook(
            new ShelfBook(UUID.randomUUID(), shelf, book, null, "기존 메모", null, null));
    Instant beforeTime = Instant.parse("2026-10-01T00:00:00Z");
    jdbcTemplate.update(
        "update shelf_books set updated_at = ? where id = ?",
        Timestamp.from(beforeTime),
        saved.id());
    Map<String, Object> before =
        jdbcTemplate.queryForMap("select * from shelf_books where id = ?", saved.id());

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> adapter.updateShelfBookMemo(saved.id(), memo));

    Map<String, Object> after =
        jdbcTemplate.queryForMap("select * from shelf_books where id = ?", saved.id());
    assertThat(after.get("memo")).isEqualTo(memo);
    assertThat(((Timestamp) after.get("updated_at")).toInstant()).isAfter(beforeTime);
    before.remove("memo");
    before.remove("updated_at");
    after.remove("memo");
    after.remove("updated_at");
    assertThat(after).isEqualTo(before);
  }

  /** 실제 CHECK 제약은 2,000자를 허용하고 초과 변경은 rollback한다. */
  @Test
  void 메모_길이_CHECK는_경계값을_허용하고_초과_변경을_취소한다() {
    UUID owner = createUser();
    UUID shelf = insertShelf(owner, "길이 서재");
    UUID book = insertUserBook(owner, "9781234567890");
    ShelfBook saved =
        adapter.createShelfBook(
            new ShelfBook(UUID.randomUUID(), shelf, book, null, "메".repeat(2000), null, null));
    Map<String, Object> before =
        jdbcTemplate.queryForMap("select * from shelf_books where id = ?", saved.id());

    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> adapter.updateShelfBookMemo(saved.id(), "메".repeat(2001))))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(jdbcTemplate.queryForMap("select * from shelf_books where id = ?", saved.id()))
        .isEqualTo(before);
  }

  /** 단건·다건 조회는 연결된 Shelf의 소유권 조건을 적용한다. */
  @Test
  void ShelfBook_조회는_본인_연결만_반환한다() {
    UUID owner = createUser();
    UUID other = createUser();
    ShelfBook owned =
        adapter.createShelfBook(
            new ShelfBook(
                UUID.randomUUID(),
                insertShelf(owner, "내 서재"),
                insertUserBook(owner, "9781234567890")));
    ShelfBook foreign =
        adapter.createShelfBook(
            new ShelfBook(
                UUID.randomUUID(),
                insertShelf(other, "타인 서재"),
                insertUserBook(other, "9781234567890")));

    assertThat(adapter.findShelfBookByIdAndUserId(owned.id(), owner)).isEqualTo(owned);
    assertThat(
            adapter.findAllShelfBookByIdAndUserId(
                List.of(owned.id(), foreign.id(), UUID.randomUUID()), owner))
        .containsExactly(owned);
    assertThatThrownBy(() -> adapter.findShelfBookByIdAndUserId(foreign.id(), owner))
        .isInstanceOfSatisfying(
            BookException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.SHELF_BOOK_NOT_FOUND));
  }

  /** JOIN FETCH한 도서 필드를 트랜잭션 밖의 DTO 변환까지 읽고 다른 서재 데이터는 제외한다. */
  @Test
  void 서재별_조회는_도서와_연결의_전체_필드를_DTO로_변환한다() {
    UUID owner = createUser();
    UUID shelf = insertShelf(owner, "목록 서재");
    UUID book = insertUserBook(owner, "9781234567890");
    jdbcTemplate.update(
        "update user_books set author = '저자', publisher = '출판사', cover_image_url = 'cover' where id"
            + " = ?",
        book);
    ShelfBook saved =
        adapter.createShelfBook(new ShelfBook(UUID.randomUUID(), shelf, book, 3, "메모", null, null));
    UUID otherShelf = insertShelf(owner, "별도 서재");
    insertShelfBook(otherShelf, insertUserBook(owner, "9781234567891"));

    assertThat(adapter.findAllShelfBooksByShelfIdAndUserId(shelf, owner))
        .containsExactly(
            new ShelfBookItem(
                saved.id(),
                book,
                "9781234567890",
                "저장된 도서",
                "저자",
                "출판사",
                "cover",
                ReadingStatus.WANT_TO_READ,
                "메모",
                3,
                saved.createdAt(),
                saved.updatedAt()));
    assertThat(adapter.findAllShelfBooksByShelfIdAndUserId(shelf, createUser())).isEmpty();
    assertThat(adapter.findAllShelfBooksByShelfIdAndUserId(UUID.randomUUID(), owner)).isEmpty();
  }

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

  @Test
  void 다른_PK로_동일_사용자_ISBN을_저장하면_등록_충돌로_변환한다() {
    UUID owner = createUser();
    String isbn = "9781234567890";
    insertUserBook(owner, isbn);
    UUID duplicateId = UUID.randomUUID();
    UserBook duplicate =
        new UserBook(duplicateId, owner, isbn, "중복 도서", null, null, null, ReadingStatus.READING);

    assertThatThrownBy(() -> adapter.createUserBook(duplicate))
        .isInstanceOfSatisfying(
            BookException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.DUPLICATE_REGISTRATION))
        .hasCauseInstanceOf(DataIntegrityViolationException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from user_books where user_id = ? and isbn = ?",
                Long.class,
                owner,
                isbn))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from user_books where id = ?", Long.class, duplicateId))
        .isZero();
  }

  @Test
  void 다른_PK로_동일_서재_도서_연결을_저장하면_등록_충돌로_변환한다() {
    UUID owner = createUser();
    UUID shelfId = insertShelf(owner, "내 서재");
    UUID bookId = insertUserBook(owner, "9781234567890");
    insertShelfBook(shelfId, bookId);
    ShelfBook duplicate = new ShelfBook(UUID.randomUUID(), shelfId, bookId);

    assertThatThrownBy(() -> adapter.createShelfBook(duplicate))
        .isInstanceOfSatisfying(
            BookException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.DUPLICATE_REGISTRATION))
        .hasCauseInstanceOf(DataIntegrityViolationException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from shelf_books where shelf_id = ? and user_book_id = ?",
                Long.class,
                shelfId,
                bookId))
        .isEqualTo(1L);
  }

  @Test
  void 사용자_FK_위반은_등록_중복으로_변환하지_않는다() {
    UUID bookId = UUID.randomUUID();
    UserBook invalid =
        new UserBook(
            bookId,
            UUID.randomUUID(),
            "9781234567890",
            "도서",
            null,
            null,
            null,
            ReadingStatus.READING);

    assertThatThrownBy(() -> adapter.createUserBook(invalid))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from user_books where id = ?", Long.class, bookId))
        .isZero();
  }

  @Test
  void 신규_UserBook의_전체_필드와_생성_수정일을_저장하고_복원한다() {
    UUID owner = createUser();
    UserBook request =
        new UserBook(
            UUID.randomUUID(),
            owner,
            "9781234567890",
            "제목",
            "저자",
            "출판사",
            "cover",
            ReadingStatus.READING,
            null,
            null);

    UserBook saved = adapter.createUserBook(request);

    assertThat(saved.id()).isEqualTo(request.id());
    assertThat(saved.createdAt()).isNotNull();
    assertThat(saved.updatedAt()).isNotNull();
    assertThat(saved)
        .usingRecursiveComparison()
        .ignoringFields("createdAt", "updatedAt")
        .isEqualTo(request);
    assertThat(adapter.findUserBookByUserIdAndIsbn(owner, request.isbn())).contains(saved);
    assertThat(adapter.findUserBookByUserIdAndIsbn(createUser(), request.isbn())).isEmpty();
    assertThat(adapter.findUserBookByUserIdAndIsbn(owner, "missing")).isEmpty();
    Map<String, Object> row =
        jdbcTemplate.queryForMap("select * from user_books where id = ?", saved.id());
    assertThat(row.get("reading_status_code")).isEqualTo("READING");
    assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(saved.createdAt());
    assertThat(((Timestamp) row.get("updated_at")).toInstant()).isEqualTo(saved.updatedAt());
  }

  @Test
  void 독서_상태_변경은_서재별_메모와_기존_메타데이터를_유지한다() {
    UUID owner = createUser();
    UUID bookId = insertUserBook(owner, "9781234567890");
    UUID shelfId = insertShelf(owner, "내 서재");
    insertShelfBook(shelfId, bookId);
    jdbcTemplate.update(
        "update shelf_books set memo = ? where shelf_id = ? and user_book_id = ?",
        "보존할 메모",
        shelfId,
        bookId);
    Map<String, Object> linkBefore =
        jdbcTemplate.queryForMap(
            "select * from shelf_books where shelf_id = ? and user_book_id = ?", shelfId, bookId);
    Instant createdAt = Instant.parse("2026-10-01T00:00:00Z");
    jdbcTemplate.update(
        "update user_books set created_at = ?, updated_at = ? where id = ?",
        Timestamp.from(createdAt),
        Timestamp.from(createdAt),
        bookId);
    Map<String, Object> before =
        jdbcTemplate.queryForMap("select * from user_books where id = ?", bookId);

    UserBook result =
        new TransactionTemplate(transactionManager)
            .execute(status -> adapter.updateReadingStatus(bookId, owner, ReadingStatus.READ));

    assertThat(result).isNotNull();
    assertThat(result.readingStatus()).isEqualTo(ReadingStatus.READ);
    assertThat(
            jdbcTemplate.queryForMap(
                "select * from shelf_books where shelf_id = ? and user_book_id = ?",
                shelfId,
                bookId))
        .isEqualTo(linkBefore);
    Map<String, Object> after =
        jdbcTemplate.queryForMap("select * from user_books where id = ?", bookId);
    assertThat(after.get("reading_status_code")).isEqualTo("READ");
    assertThat(((Timestamp) after.get("updated_at")).toInstant()).isAfter(createdAt);
    before.remove("reading_status_code");
    before.remove("updated_at");
    after.remove("reading_status_code");
    after.remove("updated_at");
    assertThat(after).isEqualTo(before);
  }

  @Test
  void 타인_도서의_상태_변경은_거부하고_원본을_보존한다() {
    UUID owner = createUser();
    UUID requester = createUser();
    UUID bookId = insertUserBook(owner, "9781234567890");
    Map<String, Object> before =
        jdbcTemplate.queryForMap("select * from user_books where id = ?", bookId);

    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .execute(
                        status ->
                            adapter.updateReadingStatus(bookId, requester, ReadingStatus.READ)))
        .isInstanceOfSatisfying(
            BookException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.USER_BOOK_NOT_FOUND));

    assertThat(jdbcTemplate.queryForMap("select * from user_books where id = ?", bookId))
        .isEqualTo(before);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "이 서재에서 다시 읽을 책")
  void ShelfBook_연결의_ID와_메모와_생성일과_nullable_위치를_저장한다(String memo) {
    UUID owner = createUser();
    UUID shelfId = insertShelf(owner, "내 서재");
    UUID bookId = insertUserBook(owner, "9781234567890");
    ShelfBook input = new ShelfBook(UUID.randomUUID(), shelfId, bookId, null, memo, null, null);
    assertThat(adapter.existsByShelfIdAndUserBookId(shelfId, bookId)).isFalse();
    assertThat(adapter.existsShelfBookByUserBookId(bookId)).isFalse();

    ShelfBook saved = adapter.createShelfBook(input);

    assertThat(saved.id()).isEqualTo(input.id());
    assertThat(saved.shelfId()).isEqualTo(shelfId);
    assertThat(saved.userBookId()).isEqualTo(bookId);
    assertThat(saved.position()).isNull();
    assertThat(saved.memo()).isEqualTo(memo);
    assertThat(saved.createdAt()).isNotNull();
    assertThat(saved.updatedAt()).isNotNull();
    Map<String, Object> row =
        jdbcTemplate.queryForMap("select * from shelf_books where id = ?", saved.id());
    assertThat(row.get("shelf_id")).isEqualTo(shelfId);
    assertThat(row.get("user_book_id")).isEqualTo(bookId);
    assertThat(row.get("position")).isNull();
    assertThat(row.get("memo")).isEqualTo(memo);
    assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(saved.createdAt());
    assertThat(((Timestamp) row.get("updated_at")).toInstant()).isEqualTo(saved.updatedAt());
    assertThat(adapter.existsByShelfIdAndUserBookId(shelfId, bookId)).isTrue();
    assertThat(adapter.existsShelfBookByUserBookId(bookId)).isTrue();
    assertThat(adapter.existsByShelfIdAndUserBookId(insertShelf(owner, "다른 서재"), bookId)).isFalse();
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
