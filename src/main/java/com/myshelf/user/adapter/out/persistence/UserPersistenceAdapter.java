package com.myshelf.user.adapter.out.persistence;

import com.myshelf.user.application.port.out.UserRepositoryPort;
import com.myshelf.user.domain.User;
import com.myshelf.user.error.UserErrorCode;
import com.myshelf.user.exception.UserException;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserPersistenceAdapter implements UserRepositoryPort {
  private final UserJpaRepository userJpaRepository;
  private static final String EMAIL_UNIQUE_CONSTRAINT = "uk_users_email";

  @Override
  public User save(User user) {
    try {
      UserEntity userEntity = UserEntity.from(user);
      UserEntity savedEntity = userJpaRepository.save(userEntity);

      return UserEntity.toDomain(savedEntity);
    } catch (DataIntegrityViolationException e) {
      if (isEmailUniqueViolation(e)) {
        throw new UserException(UserErrorCode.DUPLICATE_EMAIL);
      }
      throw e;
    }
  }

  @Override
  public boolean existsByEmail(String email) {
    return userJpaRepository.existsByEmail(email);
  }

  /**
   * Constraint 이름의 반환 형식은 DB/Hibernate 조합에 따라 달라질 수 있다. 과거 H2에서는 schema와 내부 index 정보가 함께 반환되어 완전 일치
   * 비교가 실패했다. UNIQUE 위반인지 먼저 확인하고 지정한 constraint 이름이 포함되어 있는지 판별한다. 현재
   * UserPersistenceAdapterIntegrationTest는 PostgreSQL에서 이 예외 변환을 검증한다.
   */
  private boolean isEmailUniqueViolation(DataIntegrityViolationException e) {
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
                .contains(EMAIL_UNIQUE_CONSTRAINT.toUpperCase(Locale.ROOT));
      }

      cause = cause.getCause();
    }

    return false;
  }

  @Override
  public Optional<User> findByEmail(String email) {
    return userJpaRepository.findByEmail(email).map(UserEntity::toDomain);
  }
}
