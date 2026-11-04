package com.myshelf.shelf.adapter.out.persistence;

import com.myshelf.global.persistence.ConstraintViolationMatcher;
import com.myshelf.shelf.application.port.out.ShelfRepositoryPort;
import com.myshelf.shelf.domain.Shelf;
import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ShelfPersistenceAdapter implements ShelfRepositoryPort {
  private final ShelfJpaRepository shelfJpaRepository;
  private static final String NAME_UNIQUE_CONSTRAINT = "uk_shelves_name_per_user";
  private static final String USER_FK_CONSTRAINT = "fk_shelves_user";

  @Override
  public Shelf save(Shelf shelf) {
    try {
      ShelfEntity shelfEntity = ShelfEntity.from(shelf);
      ShelfEntity savedShelf = shelfJpaRepository.save(shelfEntity);

      return ShelfEntity.toDomain(savedShelf);
    } catch (DataIntegrityViolationException e) {
      if (ConstraintViolationMatcher.matches(e, ConstraintKind.UNIQUE, NAME_UNIQUE_CONSTRAINT)) {
        throw new ShelfException(ShelfErrorCode.DUPLICATE_NAME);
      }

      if (ConstraintViolationMatcher.matches(e, ConstraintKind.FOREIGN_KEY, USER_FK_CONSTRAINT)) {
        throw new ShelfException(ShelfErrorCode.INVALID_SHELF_OWNER);
      }

      throw e;
    }
  }

  @Override
  public Optional<Shelf> findByIdAndUserId(UUID shelfId, UUID userId) {
    return shelfJpaRepository.findByIdAndUserId(shelfId, userId).map(ShelfEntity::toDomain);
  }

  @Override
  public boolean existsByUserIdAndName(UUID userId, String name) {
    return shelfJpaRepository.existsByUserIdAndName(userId, name);
  }

  @Override
  public boolean existsByUserIdAndNameExcludingSelf(Shelf shelf) {
    return shelfJpaRepository.existsByUserIdAndNameAndIdNot(
        shelf.getUserId(), shelf.getName(), shelf.getId());
  }

  @Override
  public List<Shelf> list(UUID userId) {
    List<ShelfEntity> shelfEntityList = shelfJpaRepository.findShelfEntitiesByUserId(userId);
    return shelfEntityList.stream().map(ShelfEntity::toDomain).toList();
  }

  /*
   * UPDATE는 name, description만 변경하므로 USER FK 위반 처리는 제외한다.
   * 현재 ON DELETE CASCADE가 없어 Shelf가 참조 중인 User는 삭제할 수 없다.
   *
   * TODO: 회원 삭제 구현 시 CASCADE 및 연관 데이터 삭제 정책 검토
   * - Shelf 조회 전 삭제: 404
   * - Shelf 조회 후 삭제: 동시성 예외 가능 (자동 404 처리되지 않음)
   *
   * UPDATE에서는 UNIQUE 위반(409)만 명시적으로 처리한다.
   */
  @Override
  @Transactional
  public Optional<Shelf> update(Shelf shelf) {
    Optional<ShelfEntity> found =
        shelfJpaRepository.findByIdAndUserId(shelf.getId(), shelf.getUserId());

    if (found.isEmpty()) return Optional.empty();

    try {
      ShelfEntity entity = found.get();
      entity.update(shelf.getName(), shelf.getDescription());

      shelfJpaRepository.flush();

      return Optional.of(ShelfEntity.toDomain(entity));

    } catch (DataIntegrityViolationException e) {

      if (ConstraintViolationMatcher.matches(e, ConstraintKind.UNIQUE, NAME_UNIQUE_CONSTRAINT)) {
        throw new ShelfException(ShelfErrorCode.DUPLICATE_NAME);
      }

      throw e;
    }
  }

  @Override
  @Transactional
  public boolean delete(UUID shelfId, UUID userId) {
    Optional<ShelfEntity> found = shelfJpaRepository.findByIdAndUserId(shelfId, userId);
    if (found.isEmpty()) return false;

    shelfJpaRepository.delete(found.get());
    return true;
  }
}
