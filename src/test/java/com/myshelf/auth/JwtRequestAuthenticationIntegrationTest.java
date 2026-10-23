package com.myshelf.auth;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.user.adapter.out.persistence.UserPersistenceAdapter;
import com.myshelf.user.domain.User;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** 보호된 요청이 JwtDecoder를 포함한 SecurityFilterChain을 실제로 통과하는지 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class JwtRequestAuthenticationIntegrationTest {

  private static final String PROTECTED_PATH = "/test-only/principal";

  @Autowired MockMvc mockMvc;
  @Autowired private UserPersistenceAdapter userPersistenceAdapter;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired ObjectMapper objectMapper;
  @Autowired JwtEncoder jwtEncoder;
  @Autowired JwtDecoder jwtDecoder;

  /** 토큰 없이 보호 API에 접근하면 401, Bearer 인증 헤더와 ProblemDetail을 반환한다. */
  @Test
  void requestWithoutAccessTokenIsRejectedWithUnauthorized() throws Exception {
    mockMvc.perform(get(PROTECTED_PATH)).andExpectAll(unauthorizedProblemDetail());
  }

  /** 다른 키로 서명한 JWT로 보호 API에 접근하면 401을 반환한다. */
  @Test
  void requestWithInvalidSignatureTokenIsRejectedWithUnauthorized() throws Exception {
    String tokenWithWrongSignature = issueTokenSignedWithDifferentSecret();

    mockMvc
        .perform(
            get(PROTECTED_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithWrongSignature))
        .andExpectAll(unauthorizedProblemDetail());
  }

  private String issueTokenSignedWithDifferentSecret() {
    SecretKey differentSecretKey =
        new SecretKeySpec(
            Base64.getDecoder().decode("e6o2edWAsLbWVJYXC4ycWTJgbAuOgPMhfrytnqaPfak="),
            "HmacSHA256");

    JwtEncoder differentJwtEncoder =
        NimbusJwtEncoder.withSecretKey(differentSecretKey).algorithm(MacAlgorithm.HS256).build();

    Instant now = Instant.now();

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(1800))
            .build();

    return differentJwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
  }

  /** 실제 로그인으로 발급받은 JWT로 보호 API에 접근하면 200과 사용자 UUID를 반환한다. */
  @Test
  void requestWithValidAccessTokenReturnsAuthenticatedUserId() throws Exception {
    String email = "jwt-request-" + UUID.randomUUID() + "@example.com";
    String rawPassword = "Abcdefg!";
    UUID userId = UUID.randomUUID();
    userPersistenceAdapter.save(new User(userId, email, passwordEncoder.encode(rawPassword)));

    String accessToken = loginAndGetAccessToken(email, rawPassword);

    mockMvc
        .perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(content().string(userId.toString()));
  }

  private String loginAndGetAccessToken(String email, String password) throws Exception {
    String responseBody =
        mockMvc
            .perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(new LoginRequestBody(email, password))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return objectMapper.readTree(responseBody).get("accessToken").asString();
  }

  private record LoginRequestBody(String email, String password) {}

  /** 공개 로그인 API도 잘못된 Bearer 토큰을 첨부하면 401을 반환한다. */
  @Test
  void loginWithInvalidBearerTokenReturnsUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer Invalid.jwt.token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "email": "test@example.com",
                                      "password": "wrong-password"
                                    }
                                    """))
        .andExpectAll(unauthorizedProblemDetail());
  }

  /** 정상 키로 서명했지만 만료된 JWT는 만료 검증에 실패하고 401과 ProblemDetail을 반환한다. */
  @Test
  void requestWithExpiredAccessTokenIsRejectedWithUnauthorized() throws Exception {
    Instant now = Instant.now();

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(UUID.randomUUID().toString())
            .issuedAt(now.minusSeconds(1800))
            .expiresAt(now.minusSeconds(300))
            .build();

    String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

    // 서명 오류가 아니라 만료에 의한 검증 실패인지 확인한다.
    assertThatThrownBy(() -> jwtDecoder.decode(expiredToken))
        .isInstanceOf(JwtValidationException.class)
        .hasMessageContaining("Jwt expired at");

    mockMvc
        .perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
        .andExpectAll(unauthorizedProblemDetail());
  }

  /** sub가 잘못된 문자열이거나 축약된 UUID이면 401과 ProblemDetail을 반환한다. */
  @ParameterizedTest
  @ValueSource(strings = {"invalid-user-id", "1-1-1-1-1"})
  void requestWithInvalidSubjectIsRejectedWithUnauthorized(String subject) throws Exception {
    Instant now = Instant.now();

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(1800))
            .build();

    String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

    mockMvc
        .perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpectAll(unauthorizedProblemDetail());
  }

  /** sub가 없으면 401과 ProblemDetail을 반환한다. */
  @Test
  void requestWithoutSubjectIsRejectedWithUnauthorized() throws Exception {
    Instant now = Instant.now();

    JwtClaimsSet claims =
        JwtClaimsSet.builder().issuedAt(now).expiresAt(now.plusSeconds(1800)).build();

    String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

    mockMvc
        .perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpectAll(unauthorizedProblemDetail());
  }

  private static ResultMatcher[] unauthorizedProblemDetail() {
    return new ResultMatcher[] {
      status().isUnauthorized(),
      header().exists(HttpHeaders.WWW_AUTHENTICATE),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(401),
      jsonPath("$.title").value("Auth Error"),
      jsonPath("$.code").value("AUTHENTICATION_FAILED")
    };
  }

  // 아직 보호된 비즈니스 API가 없어 테스트 전용 엔드포인트로 SecurityFilterChain을 검증.
  @TestConfiguration
  static class ProtectedProbeConfig {

    @Bean
    ProtectedProbeController protectedProbeController() {
      return new ProtectedProbeController();
    }
  }

  @RestController
  static class ProtectedProbeController {

    @GetMapping(PROTECTED_PATH)
    String principal(Authentication authentication) {
      return authentication.getName();
    }
  }
}
