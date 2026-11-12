package com.myshelf.shelf.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myshelf.shelf.domain.Shelf;
import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import com.myshelf.user.adapter.out.persistence.UserPersistenceAdapter;
import com.myshelf.user.domain.User;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 PostgreSQL의 Shelf 저장·조회·수정·삭제와 제약 위반의 예외 변환을 검증한다. 테스트 전체에 트랜잭션을 걸지 않아 운영 코드의 트랜잭션 경계와
 * commit/flush 동작을 유지한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShelfPersistenceAdapterIntegrationTest {

  @Autowired ShelfPersistenceAdapter shelfPersistenceAdapter;
  @Autowired ShelfJpaRepository shelfJpaRepository;
  @Autowired UserPersistenceAdapter userPersistenceAdapter;
  @Autowired JdbcTemplate jdbcTemplate;

  private final List<UUID> userIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (UUID userId : userIds) {
      jdbcTemplate.update("delete from shelves where user_id = ?", userId);
      jdbcTemplate.update("delete from users where id = ?", userId);
    }
  }

  /** 같은 사용자의 Shelf 이름을 사전 검사 없이 중복 저장하면 UNIQUE 위반이 DUPLICATE_NAME으로 변환된다. */
  @Test
  void duplicateNameConstraintIsTranslatedWhenPrecheckIsBypassed() {
    UUID userId = createUser();
    shelfPersistenceAdapter.save(new Shelf(UUID.randomUUID(), "같은 이름", null, userId));

    // Repository 직접 호출로 Shelf 이름 UNIQUE 위반의 제약조건 이름과 SQLState를 확인한다.
    assertThatThrownBy(
            () ->
                shelfJpaRepository.saveAndFlush(
                    new ShelfEntity(UUID.randomUUID(), "같은 이름", "설명", userId, null, null)))
        .isInstanceOfSatisfying(
            DataIntegrityViolationException.class,
            ex -> {
              ConstraintViolationException constraint = findConstraintViolation(ex);
              assertThat(constraint.getConstraintName()).isEqualTo("uk_shelves_name_per_user");
              assertThat(constraint.getSQLState()).isEqualTo("23505");
            });

    assertThatThrownBy(
            () ->
                shelfPersistenceAdapter.save(
                    new Shelf(UUID.randomUUID(), "같은 이름", "다른 설명", userId)))
        .isInstanceOfSatisfying(
            ShelfException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ShelfErrorCode.DUPLICATE_NAME));

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from shelves where user_id = ? and name = ?",
                Long.class,
                userId,
                "같은 이름"))
        .isEqualTo(1L);
  }

  /** Shelf 이름 존재 여부는 사용자별로 조회하며, 다른 사용자는 같은 Shelf 이름을 저장할 수 있다. */
  @Test
  void nameExistenceAndUniquenessAreScopedToOwner() {
    UUID firstUser = createUser();
    UUID secondUser = createUser();
    String name = "읽고 싶은 책";
    shelfPersistenceAdapter.save(new Shelf(UUID.randomUUID(), name, null, firstUser));

    assertThat(shelfPersistenceAdapter.existsByUserIdAndName(firstUser, name)).isTrue();
    assertThat(shelfPersistenceAdapter.existsByUserIdAndName(secondUser, name)).isFalse();
    assertThat(shelfPersistenceAdapter.existsByUserIdAndName(firstUser, "다른 이름")).isFalse();

    Shelf secondShelf =
        shelfPersistenceAdapter.save(new Shelf(UUID.randomUUID(), name, "다른 사용자", secondUser));

    assertThat(shelfPersistenceAdapter.existsByUserIdAndName(secondUser, name)).isTrue();
    assertThat(shelfJpaRepository.findById(secondShelf.id()))
        .hasValueSatisfying(entity -> assertThat(entity.getUserId()).isEqualTo(secondUser));
  }

  /** 존재하지 않는 사용자 UUID로 Shelf를 저장하면 fk_shelves_user 위반이 INVALID_SHELF_OWNER로 변환된다. */
  @Test
  void missingOwnerForeignKeyViolationIsTranslatedToInvalidShelfOwner() {
    UUID missingUserId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                shelfJpaRepository.saveAndFlush(
                    new ShelfEntity(UUID.randomUUID(), "내 서재", null, missingUserId, null, null)))
        .isInstanceOfSatisfying(
            DataIntegrityViolationException.class,
            ex -> {
              ConstraintViolationException constraint = findConstraintViolation(ex);
              assertThat(constraint.getConstraintName()).isEqualTo("fk_shelves_user");
              assertThat(constraint.getSQLState()).isEqualTo("23503");
            });

    assertThatThrownBy(
            () -> shelfPersistenceAdapter.save(new Shelf(shelfId, "내 서재", null, missingUserId)))
        .isInstanceOfSatisfying(
            ShelfException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ShelfErrorCode.INVALID_SHELF_OWNER));

    assertThat(shelfJpaRepository.findById(shelfId)).isEmpty();
  }

  /** 저장 시 생성·수정 시각이 채워지고, DB에서 읽은 값이 반환된 Shelf의 시각과 일치한다. */
  @Test
  void saveReturnsGeneratedAuditTimestamps() {
    UUID userId = createUser();
    Shelf saved =
        shelfPersistenceAdapter.save(new Shelf(UUID.randomUUID(), "생성 시각 확인", null, userId));

    assertThat(saved.createdAt()).isNotNull();
    assertThat(saved.updatedAt()).isNotNull();
    assertThat(shelfJpaRepository.findById(saved.id()))
        .hasValueSatisfying(
            entity -> {
              assertThat(entity.getCreatedAt()).isEqualTo(saved.createdAt());
              assertThat(entity.getUpdatedAt()).isEqualTo(saved.updatedAt());
              Shelf restored = ShelfEntity.toDomain(entity);
              assertThat(restored.createdAt()).isEqualTo(entity.getCreatedAt());
              assertThat(restored.updatedAt()).isEqualTo(entity.getUpdatedAt());
            });
  }

  /** SQL로 저장한 데이터에서 본인 소유만 조회하고 모든 필드가 Domain으로 매핑되는지 검증한다. */
  @Test
  void listFiltersByUserAndMapsPersistedFields() {
    UUID userId = createUser();
    Shelf first = insertShelf(userId, "같은 이름", "내 설명");
    Shelf second = insertShelf(userId, "설명 없는 서재", null);
    insertShelf(createUser(), "같은 이름", "타인의 설명");

    List<Shelf> actual = shelfPersistenceAdapter.list(userId);

    assertThat(actual).hasSize(2);
    for (Shelf expected : List.of(first, second)) {
      assertThat(actual)
          .filteredOn(shelf -> shelf.id().equals(expected.id()))
          .singleElement()
          .usingRecursiveComparison()
          .isEqualTo(expected);
    }
  }

  /** 타인의 데이터가 존재해도 자신의 Shelf가 없으면 빈 목록을 반환한다. */
  @Test
  void listReturnsEmptyForUserWithoutShelves() {
    UUID userId = createUser();
    insertShelf(createUser(), "타인의 서재", null);

    assertThat(shelfPersistenceAdapter.list(userId)).isEmpty();
  }

  /** 단건 조회가 실제 DB의 소유자·설명·서로 다른 생성 및 수정 시각을 보존한다. */
  @Test
  void getMapsAllPersistedFieldsForOwner() {
    UUID userId = createUser();
    Shelf expected = insertShelf(userId, "내 서재", "설명");

    assertThat(shelfPersistenceAdapter.findByIdAndUserId(expected.id(), userId))
        .hasValueSatisfying(
            actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(expected));
  }

  /** Shelf ID만으로 조회하지 않으며, 타인 소유와 없는 ID 모두 Optional.empty로 반환한다. */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void getReturnsEmptyForOtherOwnerOrMissingShelf(boolean ownedByOtherUser) {
    UUID userId = createUser();
    UUID shelfId =
        ownedByOtherUser ? insertShelf(createUser(), "타인의 서재", null).id() : UUID.randomUUID();

    assertThat(shelfPersistenceAdapter.findByIdAndUserId(shelfId, userId)).isEmpty();
  }

  /** 본인 서재의 이름·설명을 수정하고, 반환값과 DB 반영 및 생성·수정 시각을 확인한다. */
  @ParameterizedTest
  @CsvSource({"원래 이름, 새로운 설명", "새 이름, 원래 설명", "원래 이름,", "원래 이름, ''"})
  void 정상_수정_케이스(String name, String description) {
    // given
    UUID userId = createUser();
    Shelf savedShelf = insertShelf(userId, "원래 이름", "원래 설명");

    // when
    Optional<Shelf> result =
        shelfPersistenceAdapter.update(new Shelf(savedShelf.id(), name, description, userId));

    // then
    Map<String, Object> stored =
        jdbcTemplate.queryForMap("select * from shelves where id = ?", savedShelf.id());
    assertThat(stored.get("id")).isEqualTo(savedShelf.id());
    assertThat(stored.get("user_id")).isEqualTo(userId);
    assertThat(stored.get("name")).isEqualTo(name);
    assertThat(stored.get("description")).isEqualTo(description);
    assertThat(((Timestamp) stored.get("created_at")).toInstant())
        .isEqualTo(savedShelf.createdAt());
    assertThat(((Timestamp) stored.get("updated_at")).toInstant()).isAfter(savedShelf.updatedAt());
    assertThat(result)
        .hasValueSatisfying(
            actual -> {
              assertThat(actual.id()).isEqualTo(savedShelf.id());
              assertThat(actual.userId()).isEqualTo(userId);
              assertThat(actual.name()).isEqualTo(name);
              assertThat(actual.description()).isEqualTo(description);
              assertThat(actual.createdAt())
                  .isEqualTo(((Timestamp) stored.get("created_at")).toInstant());
              assertThat(actual.updatedAt())
                  .isEqualTo(((Timestamp) stored.get("updated_at")).toInstant());
            });
  }

  /** 다른 사용자의 ID로 수정을 시도하면 빈 결과를 반환하고 원본의 모든 컬럼을 보존한다. */
  @Test
  void 타인_서재는_수정하지_않는다() {
    // given
    UUID requesterId = createUser();
    UUID ownerId = createUser();
    Shelf savedShelf = insertShelf(ownerId, "타인의 서재", "기존 설명");
    Map<String, Object> before =
        jdbcTemplate.queryForMap("select * from shelves where id = ?", savedShelf.id());

    // when
    Optional<Shelf> result =
        shelfPersistenceAdapter.update(new Shelf(savedShelf.id(), "변경할 이름", "변경할 설명", requesterId));

    // then
    assertThat(result).isEmpty();
    assertThat(jdbcTemplate.queryForMap("select * from shelves where id = ?", savedShelf.id()))
        .isEqualTo(before);
  }

  /** 같은 사용자의 다른 서재 이름으로 수정하면 UNIQUE 위반을 변환하고 변경을 롤백한다. */
  @Test
  void 다른_서재와_이름이_중복되면_예외가_발생하고_원본을_유지한다() {
    // given
    UUID userId = createUser();
    insertShelf(userId, "소설", "소설 설명");
    Shelf target = insertShelf(userId, "인문", "기존 설명");
    List<Map<String, Object>> before =
        jdbcTemplate.queryForList("select * from shelves where user_id = ?", userId);

    // when, then
    assertThatThrownBy(
            () -> shelfPersistenceAdapter.update(new Shelf(target.id(), "소설", "실패할 설명 변경", userId)))
        .isInstanceOfSatisfying(
            ShelfException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ShelfErrorCode.DUPLICATE_NAME));

    assertThat(jdbcTemplate.queryForList("select * from shelves where user_id = ?", userId))
        .containsExactlyInAnyOrderElementsOf(before);
  }

  /** 본인 서재 삭제는 commit 후 실제 행이 사라지며 다른 사용자의 서재는 유지한다. */
  @Test
  void 본인_서재를_삭제한다() {
    // given
    UUID userId = createUser();
    Shelf target = insertShelf(userId, "삭제할 서재", null);
    Shelf other = insertShelf(createUser(), "남겨둘 서재", "다른 사용자");
    Map<String, Object> otherBefore =
        jdbcTemplate.queryForMap("select * from shelves where id = ?", other.id());

    // when
    boolean deleted = shelfPersistenceAdapter.delete(target.id(), userId);

    // then
    assertThat(deleted).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from shelves where id = ?", Long.class, target.id()))
        .isZero();
    assertThat(jdbcTemplate.queryForMap("select * from shelves where id = ?", other.id()))
        .isEqualTo(otherBefore);
  }

  /** 다른 사용자의 ID로 삭제를 시도하면 false를 반환하고 원본을 보존한다. */
  @Test
  void 타인_서재는_삭제하지_않는다() {
    // given
    UUID requesterId = createUser();
    Shelf target = insertShelf(createUser(), "타인의 서재", "기존 설명");
    Map<String, Object> before =
        jdbcTemplate.queryForMap("select * from shelves where id = ?", target.id());

    // when
    boolean deleted = shelfPersistenceAdapter.delete(target.id(), requesterId);

    // then
    assertThat(deleted).isFalse();
    assertThat(jdbcTemplate.queryForMap("select * from shelves where id = ?", target.id()))
        .isEqualTo(before);
  }

  // 저장 로직의 변환에 의존하지 않고, DB에 직접 넣은 알려진 값을 조회해 검증한다.
  private Shelf insertShelf(UUID userId, String name, String description) {
    UUID shelfId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-10-01T01:02:03Z");
    Instant updatedAt = Instant.parse("2026-10-02T04:05:06Z");
    jdbcTemplate.update(
        """
        insert into shelves (id, user_id, name, description, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?)
        """,
        shelfId,
        userId,
        name,
        description,
        Timestamp.from(createdAt),
        Timestamp.from(updatedAt));
    return new Shelf(shelfId, name, description, userId, createdAt, updatedAt);
  }

  private UUID createUser() {
    UUID userId = UUID.randomUUID();
    userIds.add(userId);
    userPersistenceAdapter.save(
        new User(userId, "shelf-adapter-" + userId + "@example.com", "encoded-password"));
    return userId;
  }

  private static ConstraintViolationException findConstraintViolation(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException constraint) {
        return constraint;
      }
    }
    throw new AssertionError("Hibernate ConstraintViolationException이 원인 체인에 없습니다.", exception);
  }
}
