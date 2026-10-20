package com.myshelf.auth.adapter.out.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 실제 JWT 발급과 디코딩으로 sub, iat, exp를 검증한다. */
class JwtAccessTokenAdapterTest {

  // 테스트 전용 32byte(256bit) 고정 secret. 운영 secret과 무관하다.
  private static final String TEST_SECRET = "3GaoMboqMPEL00bl2eo4f7ZwIm0bPFhaAz7dvBAX3uo=";
  private static final Duration ACCESS_TOKEN_EXPIRATION = Duration.ofMinutes(30);

  private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

  private JwtAccessTokenAdapter jwtAccessTokenAdapter;
  private NimbusJwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    SecretKey secretKey = new SecretKeySpec(Base64.getDecoder().decode(TEST_SECRET), "HmacSHA256");

    JwtEncoder jwtEncoder =
        NimbusJwtEncoder.withSecretKey(secretKey).algorithm(MacAlgorithm.HS256).build();
    jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();

    Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
    timestampValidator.setClock(clock);
    jwtDecoder.setJwtValidator(timestampValidator);

    JwtProperties jwtProperties = new JwtProperties(TEST_SECRET, ACCESS_TOKEN_EXPIRATION);

    jwtAccessTokenAdapter = new JwtAccessTokenAdapter(jwtEncoder, jwtProperties, clock);
  }

  @Test
  void issuedTokenIsDecodableAndContainsExpectedClaims() {
    UUID userId = UUID.randomUUID();

    String token = jwtAccessTokenAdapter.issue(userId);
    Jwt decoded = jwtDecoder.decode(token);

    assertThat(decoded.getSubject()).isEqualTo(userId.toString());
    assertThat(decoded.getIssuedAt()).isEqualTo(FIXED_NOW);
    assertThat(decoded.getExpiresAt()).isEqualTo(FIXED_NOW.plus(ACCESS_TOKEN_EXPIRATION));
  }
}
