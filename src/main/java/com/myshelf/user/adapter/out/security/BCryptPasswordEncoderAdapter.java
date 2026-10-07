package com.myshelf.user.adapter.out.security;

import com.myshelf.user.application.port.out.PasswordEncoderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BCryptPasswordEncoderAdapter implements PasswordEncoderPort {
  private final PasswordEncoder passwordEncoder;

  @Override
  public String encode(String rawpassword) {
    return passwordEncoder.encode(rawpassword);
  }
}
