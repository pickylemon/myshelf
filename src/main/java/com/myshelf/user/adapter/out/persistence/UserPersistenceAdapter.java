package com.myshelf.user.adapter.out.persistence;

import com.myshelf.global.persistence.ConstraintViolationMatcher;
import com.myshelf.user.application.port.out.UserRepositoryPort;
import com.myshelf.user.domain.User;
import com.myshelf.user.error.UserErrorCode;
import com.myshelf.user.exception.UserException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
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
      if (ConstraintViolationMatcher.matches(e, ConstraintKind.UNIQUE, EMAIL_UNIQUE_CONSTRAINT)) {
        throw new UserException(UserErrorCode.DUPLICATE_EMAIL);
      }
      throw e;
    }
  }

  @Override
  public boolean existsByEmail(String email) {
    return userJpaRepository.existsByEmail(email);
  }

  @Override
  public Optional<User> findByEmail(String email) {
    return userJpaRepository.findByEmail(email).map(UserEntity::toDomain);
  }
}
