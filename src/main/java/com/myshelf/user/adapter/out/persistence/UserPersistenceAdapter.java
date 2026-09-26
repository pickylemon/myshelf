package com.myshelf.user.adapter.out.persistence;

import com.myshelf.user.application.port.out.UserRepositoryPort;
import com.myshelf.user.domain.User;
import com.myshelf.user.error.UserErrorCode;
import com.myshelf.user.exception.UserException;
import java.util.Locale;
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
   * H2/Hibernate가 만드는 constraint 이름에는 schema 접두사와 내부 index 이름이 덧붙는다 (예: "PUBLIC.UK_USERS_EMAIL
   * INDEX PUBLIC.UK_USERS_EMAIL_INDEX_4"). 이 포맷 자체가 DB/Hibernate 조합의 구현 세부사항이라 정확히 잘라내는 파싱은 그 포맷에
   * 그대로 결합된다. 대신 UNIQUE 위반인지 먼저 확인하고, 그 안에 우리가 지정한 constraint 이름이 포함돼 있는지만 본다 — 포맷이 다소 바뀌어도(스키마 표기
   * 방식, "INDEX" 문구 등) 이름 자체만 살아있으면 계속 동작한다. 포맷이 아예 달라지면 UserPersistenceAdapterIntegrationTest가 실패로
   * 알려준다.
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
}
