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

/** 인증 성공 시 토큰 발급과 인증 실패 시 예외 전파를 검증한다. */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock AuthenticationPort authenticationPort;

  @Mock AccessTokenIssuerPort accessTokenIssuerPort;

  AuthService authService;

  @BeforeEach
  void setUp() {
    authService = new AuthService(authenticationPort, accessTokenIssuerPort);
  }

  @Test
  void loginReturnsAccessTokenWhenAuthenticationSucceeds() {
    // given
    String email = "user@example.com";
    String rawPassword = "raw-password";
    UUID userId = UUID.randomUUID();
    String issuedToken = "issued-access-token";

    when(authenticationPort.authenticate(email, rawPassword))
        .thenReturn(new AuthenticatedUser(userId));
    when(accessTokenIssuerPort.issue(userId)).thenReturn(issuedToken);

    // when
    String result = authService.login(new LoginCommand(email, rawPassword));

    // then
    assertThat(result).isEqualTo(issuedToken);
    verify(accessTokenIssuerPort).issue(userId);
  }

  @Test
  void loginPropagatesAuthExceptionWithoutIssuingTokenWhenAuthenticationFails() {
    // given
    String email = "user@example.com";
    String rawPassword = "wrong-password";

    when(authenticationPort.authenticate(email, rawPassword))
        .thenThrow(new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

    // when + then
    assertThatThrownBy(() -> authService.login(new LoginCommand(email, rawPassword)))
        .isInstanceOfSatisfying(
            AuthException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));

    // then
    verify(accessTokenIssuerPort, never()).issue(any());
  }
}
