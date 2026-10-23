package com.myshelf.auth.adapter.out.token;

import java.time.Clock;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

// JWT 관련 bean 생성
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {
  // RFC 7518에 따라 32바이트 이상이어야 함
  private static final int MINIMUM_SECRET_BYTE_LENGTH = 32;

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public SecretKey jwtSecretKey(JwtProperties properties) {
    // secret -> SecretKey
    byte[] keyBytes = Base64.getDecoder().decode(properties.secret());

    if (keyBytes.length < MINIMUM_SECRET_BYTE_LENGTH) {
      throw new IllegalStateException(
          "jwt.secret must be at least "
              + MINIMUM_SECRET_BYTE_LENGTH
              + " bytes when Base64-decoded for HS256, but was "
              + keyBytes.length
              + " bytes.");
    }

    return new SecretKeySpec(keyBytes, "HmacSHA256");
  }

  @Bean
  public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
    // SecretKey -> NimbusJwtEncoder
    return NimbusJwtEncoder.withSecretKey(jwtSecretKey).algorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
    NimbusJwtDecoder jwtDecoder =
        NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();

    JwtClaimValidator<String> subjectValidator =
        new JwtClaimValidator<>(
            JwtClaimNames.SUB,
            // 기존 방식: UUID로 변환한 뒤 표준 문자열과 비교한다.
            // subject -> {
            //   try {
            //     return UUID.fromString(subject).toString().equalsIgnoreCase(subject);
            //   } catch (IllegalArgumentException e) {
            //     return false;
            //   }
            // }
            subject ->
                subject.matches(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));

    jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithValidators(subjectValidator));

    return jwtDecoder;
  }
}
