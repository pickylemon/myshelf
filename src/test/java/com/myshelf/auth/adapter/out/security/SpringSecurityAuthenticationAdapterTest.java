package com.myshelf.auth.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myshelf.auth.application.port.out.AuthenticatedUser;
import com.myshelf.auth.error.AuthErrorCode;
import com.myshelf.auth.exception.AuthException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Spring Security Provider를 통한 인증 결과와 사용자 미존재 시 timing side-channel 완화 동작을 검증한다. */
@ExtendWith(MockitoExtension.class)
class SpringSecurityAuthenticationAdapterTest {
  private static final String EMAIL = "user@example.com";
  private static final String PASSWORD = "raw-password";
  private static final String ENCODED_PASSWORD = "encoded-password";
  private static final String DUMMY_ENCODED_PASSWORD = "dummy-password-hash";

  @Mock UserDetailsService userDetailsService;
  @Mock PasswordEncoder passwordEncoder;

  private SpringSecurityAuthenticationAdapter adapter;

  @BeforeEach
  void setUp() {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    adapter = new SpringSecurityAuthenticationAdapter(new ProviderManager(provider));
    when(passwordEncoder.encode(anyString())).thenReturn("dummy-password-hash");
  }

  @Test
  void validCredentialsReturnAuthenticatedUser() {
    // given
    UUID userId = UUID.randomUUID();
    when(userDetailsService.loadUserByUsername(EMAIL))
        .thenReturn(new AuthUserPrincipal(userId, EMAIL, ENCODED_PASSWORD));
    when(passwordEncoder.matches(PASSWORD, ENCODED_PASSWORD)).thenReturn(true);

    // when, then
    assertThat(adapter.authenticate(EMAIL, PASSWORD)).isEqualTo(new AuthenticatedUser(userId));
    verify(passwordEncoder).matches(PASSWORD, ENCODED_PASSWORD);
  }

  @Test
  void wrongPasswordReturnsInvalidCredentials() {
    AuthUserPrincipal principal = new AuthUserPrincipal(UUID.randomUUID(), EMAIL, ENCODED_PASSWORD);

    when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(principal);

    // Mockito 기본값은 false지만, 비밀번호 불일치 시나리오를 명시한다.
    when(passwordEncoder.matches(PASSWORD, principal.getPassword())).thenReturn(false);

    assertAuthenticationFailsWithInvalidCredentials();

    verify(passwordEncoder).matches(PASSWORD, principal.getPassword());
  }

  @Test
  void missingUserReturnsInvalidCredentialsAndStillComparesPassword() {
    when(userDetailsService.loadUserByUsername(EMAIL))
        .thenThrow(new UsernameNotFoundException("User not found"));

    assertAuthenticationFailsWithInvalidCredentials();

    // 존재하지 않는 사용자여도 password comparison을 수행하여 timing side-channel을 완화
    verify(passwordEncoder).matches(PASSWORD, DUMMY_ENCODED_PASSWORD);
  }

  private void assertAuthenticationFailsWithInvalidCredentials() {
    assertThatThrownBy(() -> adapter.authenticate(EMAIL, PASSWORD))
        .isInstanceOfSatisfying(
            AuthException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
  }
}
