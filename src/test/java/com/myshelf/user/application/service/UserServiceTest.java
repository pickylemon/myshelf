package com.myshelf.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myshelf.user.application.port.in.UserSignUpCommand;
import com.myshelf.user.application.port.out.PasswordEncoderPort;
import com.myshelf.user.application.port.out.UserRepositoryPort;
import com.myshelf.user.domain.User;
import com.myshelf.user.error.UserErrorCode;
import com.myshelf.user.exception.UserException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * UserService의 회원가입 비즈니스 흐름을 UserRepositoryPort/PasswordEncoderPort를 mock한 채로 검증한다. Spring 컨텍스트나
 * DB는 필요 없다.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock UserRepositoryPort userRepositoryPort;

  @Mock PasswordEncoderPort passwordEncoderPort;

  UserService userService;

  @BeforeEach
  void setUp() {
    userService = new UserService(userRepositoryPort, passwordEncoderPort);
  }

  /** 이미 존재하는 이메일이면 DUPLICATE_EMAIL 예외를 던지고, 비밀번호 인코딩이나 저장 시도는 일어나지 않아야 한다(불필요한 부수효과 방지). */
  @Test
  void signUpFailsWithoutSideEffectsWhenEmailAlreadyExists() {
    String email = "duplicate@example.com";
    when(userRepositoryPort.existsByEmail(email)).thenReturn(true);

    UserSignUpCommand command = new UserSignUpCommand(email, "raw-password");

    assertThatThrownBy(() -> userService.signUp(command))
        .isInstanceOfSatisfying(
            UserException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(UserErrorCode.DUPLICATE_EMAIL));

    verify(passwordEncoderPort, never()).encode(anyString());
    verify(userRepositoryPort, never()).save(any());
  }

  /** 정상 가입 시 저장 대상 User에는 평문이 아니라 인코딩된 비밀번호가 담겨야 하고, 저장 결과의 ID가 그대로 반환되어야 한다. */
  @Test
  void signUpSavesUserWithEncodedPasswordAndReturnsSavedId() {
    String email = "new-user@example.com";
    String rawPassword = "raw-password";
    String encodedPassword = "encoded-password";
    UUID savedId = UUID.randomUUID();

    when(userRepositoryPort.existsByEmail(email)).thenReturn(false);
    when(passwordEncoderPort.encode(rawPassword)).thenReturn(encodedPassword);
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(
            invocation -> {
              User passed = invocation.getArgument(0);
              return new User(savedId, passed.getEmail(), passed.getPassword());
            });

    UUID result = userService.signUp(new UserSignUpCommand(email, rawPassword));

    ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
    verify(passwordEncoderPort).encode(rawPassword);
    verify(userRepositoryPort).save(savedUserCaptor.capture());

    User savedUser = savedUserCaptor.getValue();
    assertThat(savedUser.getEmail()).isEqualTo(email);
    assertThat(savedUser.getPassword()).isEqualTo(encodedPassword);
    assertThat(result).isEqualTo(savedId);
  }
}
