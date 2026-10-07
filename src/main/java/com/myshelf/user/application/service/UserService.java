package com.myshelf.user.application.service;

import com.myshelf.global.util.UuidGenerator;
import com.myshelf.user.application.port.in.FindUserByEmailUseCase;
import com.myshelf.user.application.port.in.UserSignUpCommand;
import com.myshelf.user.application.port.in.UserSignUpUseCase;
import com.myshelf.user.application.port.out.PasswordEncoderPort;
import com.myshelf.user.application.port.out.UserRepositoryPort;
import com.myshelf.user.domain.User;
import com.myshelf.user.error.UserErrorCode;
import com.myshelf.user.exception.UserException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService implements UserSignUpUseCase, FindUserByEmailUseCase {
  private final UserRepositoryPort userRepositoryPort;
  private final PasswordEncoderPort passwordEncoderPort;

  @Override
  public UUID signUp(UserSignUpCommand command) {
    // 1. email 중복 검사
    String email = command.email();
    if (userRepositoryPort.existsByEmail(email)) {
      throw new UserException(UserErrorCode.DUPLICATE_EMAIL);
    }

    // 2. userId 및 password encoding
    String encodedPassword = passwordEncoderPort.encode(command.password());

    // 3. user 도메인 객체 생성
    UUID userId = UuidGenerator.generate(); // v7 UUID 생성
    User user = new User(userId, email, encodedPassword);

    // 4. 영속화
    return userRepositoryPort.save(user).getId();
  }

  @Override
  public Optional<User> findByEmail(String email) {
    return userRepositoryPort.findByEmail(email);
  }
}
