package com.myshelf.auth.adapter.out.token;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// JWT 설정값 보관 (application.yaml의 jwt 설정을 자바 객체로 표현)
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, Duration accessTokenExpiration) {

  @Override
  // secret은 로그에도 남기지 않도록 한다.
  public String toString() {
    return "JwtProperties[secret=****, accessTokenExpiration=" + accessTokenExpiration + "]";
  }
}
