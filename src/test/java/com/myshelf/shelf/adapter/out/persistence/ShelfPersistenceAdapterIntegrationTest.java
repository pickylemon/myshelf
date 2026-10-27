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
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 PostgreSQL의 Shelf 이름 UNIQUE·소유자 FK와 예외 변환을 검증한다. 테스트 전체에 트랜잭션을 걸지 않아 Repository의 commit/flush
 * 실패가 Adapter의 catch까지 전달되는 운영 흐름을 유지한다.
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
    assertThat(shelfJpaRepository.findById(secondShelf.getId()))
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

    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
    assertThat(shelfJpaRepository.findById(saved.getId()))
        .hasValueSatisfying(
            entity -> {
              assertThat(entity.getCreatedAt()).isEqualTo(saved.getCreatedAt());
              assertThat(entity.getUpdatedAt()).isEqualTo(saved.getUpdatedAt());
              Shelf restored = ShelfEntity.toDomain(entity);
              assertThat(restored.getCreatedAt()).isEqualTo(entity.getCreatedAt());
              assertThat(restored.getUpdatedAt()).isEqualTo(entity.getUpdatedAt());
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
          .filteredOn(shelf -> shelf.getId().equals(expected.getId()))
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

    assertThat(shelfPersistenceAdapter.findByIdAndUserId(expected.getId(), userId))
        .hasValueSatisfying(
            actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(expected));
  }

  /** Shelf ID만으로 조회하지 않으며, 타인 소유와 없는 ID 모두 Optional.empty로 반환한다. */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void getReturnsEmptyForOtherOwnerOrMissingShelf(boolean ownedByOtherUser) {
    UUID userId = createUser();
    UUID shelfId =
        ownedByOtherUser ? insertShelf(createUser(), "타인의 서재", null).getId() : UUID.randomUUID();

    assertThat(shelfPersistenceAdapter.findByIdAndUserId(shelfId, userId)).isEmpty();
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
