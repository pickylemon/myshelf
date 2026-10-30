package com.myshelf.auth.adapter.in.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public final class JwtUserIdResolver {
  public static UUID resolveUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
