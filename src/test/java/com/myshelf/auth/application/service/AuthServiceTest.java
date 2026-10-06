package com.myshelf.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myshelf.auth.application.port.in.LoginCommand;
import com.myshelf.auth.application.port.out.AccessTokenIssuerPort;
import com.myshelf.auth.application.port.out.AuthenticatedUser;
import com.myshelf.auth.application.port.out.AuthenticationPort;
import com.myshelf.auth.error.AuthErrorCode;
import com.myshelf.auth.exception.AuthException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AuthService의 로그인 orchestration을 AuthenticationPort/AccessTokenIssuerPort를 mock한 채로 검증한다.
 *
 * <p>사용자 조회·비밀번호 비교·사용자 미존재와 비밀번호 불일치를 구분하지 않는 판단은 전부 AuthenticationPort
 * 구현체(SpringSecurityAuthenticationAdapter)의 책임으로 옮겨갔다. AuthService는 그 결과(성공 시 AuthenticatedUser, 실패
 * 시 AuthException)만 받아서 토큰 발급 여부를 조율하므로, 이 테스트도 그 좁아진 책임만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock AuthenticationPort authenticationPort;

  @Mock AccessTokenIssuerPort accessTokenIssuerPort;

  AuthService authService;

  @BeforeEach
  void setUp() {
    authService = new AuthService(authenticationPort, accessTokenIssuerPort);
  }

  /** 인증에 성공하면 인증된 사용자의 ID로 발급받은 accessToken을 그대로 반환해야 한다. */
  @Test
  void loginReturnsAccessTokenWhenAuthenticationSucceeds() {
    String email = "user@example.com";
    String rawPassword = "raw-password";
    UUID userId = UUID.randomUUID();
    String issuedToken = "issued-access-token";

    when(authenticationPort.authenticate(email, rawPassword))
        .thenReturn(new AuthenticatedUser(userId));
    when(accessTokenIssuerPort.issue(userId)).thenReturn(issuedToken);

    String result = authService.login(new LoginCommand(email, rawPassword));

    assertThat(result).isEqualTo(issuedToken);
    verify(accessTokenIssuerPort).issue(userId);
  }

  /**
   * 인증에 실패하면(AuthenticationPort가 AuthException을 던지면) 그 예외가 그대로 전파되어야 하고, AccessTokenIssuerPort는
   * 호출되지 않아야 한다.
   */
  @Test
  void loginPropagatesAuthExceptionWithoutIssuingTokenWhenAuthenticationFails() {
    String email = "user@example.com";
    String rawPassword = "wrong-password";

    when(authenticationPort.authenticate(email, rawPassword))
        .thenThrow(new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

    assertThatThrownBy(() -> authService.login(new LoginCommand(email, rawPassword)))
        .isInstanceOfSatisfying(
            AuthException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));

    verify(accessTokenIssuerPort, never()).issue(any());
  }
}
