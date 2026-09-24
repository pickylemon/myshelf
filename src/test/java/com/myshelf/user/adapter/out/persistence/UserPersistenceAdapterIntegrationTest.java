package com.myshelf.user.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myshelf.user.domain.User;
import com.myshelf.user.error.UserErrorCode;
import com.myshelf.user.exception.UserException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 DB unique constraint 위반이 UserException(DUPLICATE_EMAIL)으로 변환되는지 검증한다.
 *
 * <p>existsByEmail 사전 검사를 우회하기 위해 UserPersistenceAdapter를 직접 호출한다. @DataJpaTest는 테스트 메서드 전체를 트랜잭션으로
 * 감싸므로 Repository의 save()가 해당 트랜잭션에 참여하게 되고, flush 및 constraint violation 발생 시점이 실제 운영 흐름과 달라질 수
 * 있다.
 *
 * <p>현재 운영 코드와 동일하게 Repository transaction 종료 과정에서 발생한 예외가 Adapter로 전파되는 흐름을 검증하기
 * 위해 @SpringBootTest를 사용한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class UserPersistenceAdapterIntegrationTest {

  @Autowired UserPersistenceAdapter userPersistenceAdapter;

  /** 같은 이메일을 직접 두 번 저장했을 때 DB unique constraint 위반이 DUPLICATE_EMAIL로 변환되는지 검증한다. */
  @Test
  void savingSameEmailTwiceDirectlyTranslatesToDuplicateEmailException() {
    String email = "adapter-" + UUID.randomUUID() + "@example.com";

    userPersistenceAdapter.save(new User(UUID.randomUUID(), email, "encoded-password-1"));

    assertThatThrownBy(
            () ->
                userPersistenceAdapter.save(
                    new User(UUID.randomUUID(), email, "encoded-password-2")))
        .isInstanceOfSatisfying(
            UserException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(UserErrorCode.DUPLICATE_EMAIL));
  }

  /**
   * User -> UserEntity.from() -> DB -> UserEntity -> toDomain() -> User 왕복 과정에서 필드가 누락되거나 순서가 뒤바뀌지
   * 않고 그대로 복원되어야 한다.
   */
  @Test
  void savePersistsAndRestoresUser() {
    User user =
        new User(
            UUID.randomUUID(), "adapter-" + UUID.randomUUID() + "@example.com", "encoded-password");

    User savedUser = userPersistenceAdapter.save(user);

    assertThat(savedUser.getId()).isEqualTo(user.getId());
    assertThat(savedUser.getEmail()).isEqualTo(user.getEmail());
    assertThat(savedUser.getPassword()).isEqualTo(user.getPassword());
  }
}
