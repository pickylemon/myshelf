package com.myshelf.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.user.adapter.out.persistence.UserPersistenceAdapter;
import com.myshelf.user.domain.User;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
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
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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

  /** Authorization 헤더가 없으면 보호된 요청은 401과 WWW-Authenticate 헤더로 거부되어야 한다. */
  @Test
  void requestWithoutAccessTokenIsRejectedWithUnauthorized() throws Exception {
    mockMvc
        .perform(get(PROTECTED_PATH))
        .andDo(print())
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
  }

  // 의도적으로 다른 secret으로 서명된 토큰을 발급한다.
  @Test
  void requestWithInvalidSignatureTokenIsRejectedWithUnauthorized() throws Exception {
    String tokenWithWrongSignature = issueTokenSignedWithDifferentSecret();

    mockMvc
        .perform(
            get(PROTECTED_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithWrongSignature))
        .andDo(print())
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
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

  /** 실제 로그인으로 발급받은 액세스 토큰으로 요청하면 인증된 사용자 UUID를 응답받아야 한다. */
  @Test
  void requestWithValidAccessTokenReturnsAuthenticatedUserId() throws Exception {
    String email = "jwt-request-" + UUID.randomUUID() + "@example.com";
    String rawPassword = "Abcdefg!";
    UUID userId = UUID.randomUUID();
    userPersistenceAdapter.save(new User(userId, email, passwordEncoder.encode(rawPassword)));

    String accessToken = loginAndGetAccessToken(email, rawPassword);

    mockMvc
        .perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andDo(print())
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
