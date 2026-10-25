package com.myshelf.shelf.adapter.out.persistence;

import com.myshelf.shelf.application.port.out.ShelfRepositoryPort;
import com.myshelf.shelf.domain.Shelf;
import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ShelfPersistenceAdapter implements ShelfRepositoryPort {
  private final ShelfJpaRepository shelfJpaRepository;
  private static final String NAME_UNIQUE_CONSTRAINT = "uk_shelves_name_per_user";

  @Override
  public Shelf save(Shelf shelf) {
    try {
      ShelfEntity shelfEntity = ShelfEntity.from(shelf);
      ShelfEntity savedShelf = shelfJpaRepository.save(shelfEntity);

      return ShelfEntity.toDomain(savedShelf);
    } catch (DataIntegrityViolationException e) {
      if (isNameUniqueViolation(e)) {
        throw new ShelfException(ShelfErrorCode.DUPLICATE_NAME);
      }
      throw e;
    }
  }

  @Override
  public boolean existsByUserIdAndName(UUID userId, String name) {
    return shelfJpaRepository.existsByUserIdAndName(userId, name);
  }

  private boolean isNameUniqueViolation(DataIntegrityViolationException e) {
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof ConstraintViolationException constraintException) {
        if (constraintException.getKind() != ConstraintViolationException.ConstraintKind.UNIQUE) {
          return false;
        }

        String constraintName = constraintException.getConstraintName();
        return constraintName != null
            && constraintName
                .toUpperCase(Locale.ROOT)
                .contains(NAME_UNIQUE_CONSTRAINT.toUpperCase(Locale.ROOT));
      }

      cause = cause.getCause();
    }

    return false;
  }
}
