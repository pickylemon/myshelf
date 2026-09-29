package com.myshelf.auth.application.service;

import com.myshelf.auth.application.port.in.LoginCommand;
import com.myshelf.auth.application.port.in.LoginUseCase;
import com.myshelf.auth.application.port.out.AccessTokenIssuerPort;
import com.myshelf.auth.application.port.out.PasswordMatcherPort;
import com.myshelf.auth.error.AuthErrorCode;
import com.myshelf.auth.exception.AuthException;
import com.myshelf.user.application.port.in.FindUserByEmailUseCase;
import com.myshelf.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService implements LoginUseCase {
  private final FindUserByEmailUseCase findUserByEmailUseCase;
  private final PasswordMatcherPort passwordMatcherPort;
  private final AccessTokenIssuerPort accessTokenIssuerPort;

  @Override
  public String login(LoginCommand loginCommand) {
    // 1. User 찾기
    User foundUser =
        findUserByEmailUseCase
            .findByEmail(loginCommand.email())
            .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

    // 2. 비밀번호 대조
    if (!passwordMatcherPort.matches(loginCommand.password(), foundUser.getPassword())) {
      throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
    }

    // 3. AccessToken 발급 및 반환
    return accessTokenIssuerPort.issue(foundUser.getId());
  }
}
