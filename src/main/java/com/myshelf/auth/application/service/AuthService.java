package com.myshelf.auth.application.service;

import com.myshelf.auth.application.port.in.LoginCommand;
import com.myshelf.auth.application.port.in.LoginUseCase;
import com.myshelf.auth.application.port.out.AccessTokenIssuerPort;
import com.myshelf.auth.application.port.out.AuthenticatedUser;
import com.myshelf.auth.application.port.out.AuthenticationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService implements LoginUseCase {
  private final AuthenticationPort authenticationPort;
  private final AccessTokenIssuerPort accessTokenIssuerPort;

  @Override
  public String login(LoginCommand loginCommand) {
    // 사용자 조회와 비밀번호 검증은 인증 포트에 위임한다.
    AuthenticatedUser user =
        authenticationPort.authenticate(loginCommand.email(), loginCommand.password());

    // 2. AccessToken 발급 및 반환
    return accessTokenIssuerPort.issue(user.userId());
  }
}
