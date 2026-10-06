package com.myshelf.auth.adapter.out.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * JwtAccessTokenAdapter가 발급하는 토큰의 claim이 실제로 올바른지 검증한다.
 *
 * <p>테스트 전용 SecretKey로 실제 NimbusJwtEncoder를 구성해 토큰을 발급하고, 같은 키로 구성한 NimbusJwtDecoder로 다시 디코딩해서
 * sub/iat/exp claim을 확인한다. 토큰 문자열이 비어있지 않은지만 보는 테스트로는 claim 내용의 정확성을 검증할 수 없어서 실제 인코딩/디코딩 왕복을 거친다.
 */
class JwtAccessTokenAdapterTest {

  // 테스트 전용 32byte(256bit) 무작위 secret. 운영 secret과 무관하다.
  private static final String TEST_SECRET = "3GaoMboqMPEL00bl2eo4f7ZwIm0bPFhaAz7dvBAX3uo=";

  private JwtAccessTokenAdapter jwtAccessTokenAdapter;
  private NimbusJwtDecoder jwtDecoder;
  private Duration accessTokenExpiration;

  @BeforeEach
  void setUp() {
    SecretKey secretKey = new SecretKeySpec(Base64.getDecoder().decode(TEST_SECRET), "HmacSHA256");

    JwtEncoder jwtEncoder =
        NimbusJwtEncoder.withSecretKey(secretKey).algorithm(MacAlgorithm.HS256).build();
    jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();

    accessTokenExpiration = Duration.ofMinutes(30);
    JwtProperties jwtProperties = new JwtProperties(TEST_SECRET, accessTokenExpiration);

    jwtAccessTokenAdapter = new JwtAccessTokenAdapter(jwtEncoder, jwtProperties);
  }

  /**
   * 발급된 토큰은 현재 설정된 key/algorithm으로 디코딩 가능해야 하고, sub는 발급 대상 userId와 같아야 하며, iat/exp claim이 존재하고 그
   * 차이가 accessTokenExpiration과 같아야 한다.
   */
  @Test
  void issuedTokenIsDecodableAndContainsExpectedClaims() {
    UUID userId = UUID.randomUUID();

    String token = jwtAccessTokenAdapter.issue(userId);
    Jwt decoded = jwtDecoder.decode(token);

    assertThat(decoded.getSubject()).isEqualTo(userId.toString());
    assertThat(decoded.getIssuedAt()).isNotNull();
    assertThat(decoded.getExpiresAt()).isNotNull();
    assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
        .isEqualTo(accessTokenExpiration);
  }

  /** iat는 토큰 발급 시점 근처의 현재 시각이어야 한다 (정확한 순간이 아니라 합리적인 범위 안인지만 확인한다). */
  @Test
  void issuedAtIsCloseToCurrentTime() {
    Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    String token = jwtAccessTokenAdapter.issue(UUID.randomUUID());
    Jwt decoded = jwtDecoder.decode(token);

    Instant after = Instant.now().plusSeconds(1);

    assertThat(decoded.getIssuedAt()).isBetween(before, after);
  }
}
