package com.myshelf.auth.adapter.out.token;

import com.myshelf.auth.application.port.out.AccessTokenIssuerPort;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtAccessTokenAdapter implements AccessTokenIssuerPort {
  private final JwtEncoder jwtEncoder;
  private final JwtProperties jwtProperties;

  @Override
  public String issue(UUID userId) {
    Instant now = Instant.now();

    JwtClaimsSet jwtClaimsSet =
        JwtClaimsSet.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(jwtProperties.accessTokenExpiration()))
            .build();

    return jwtEncoder.encode(JwtEncoderParameters.from(jwtClaimsSet)).getTokenValue();
  }
}
