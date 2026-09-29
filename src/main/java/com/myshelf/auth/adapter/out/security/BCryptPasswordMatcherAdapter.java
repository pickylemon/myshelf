package com.myshelf.auth.adapter.out.security;

import com.myshelf.auth.application.port.out.PasswordMatcherPort;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BCryptPasswordMatcherAdapter implements PasswordMatcherPort {
  private final PasswordEncoder passwordEncoder;

  @Override
  public boolean matches(String rawPassword, String encodedPassword) {
    return passwordEncoder.matches(rawPassword, encodedPassword);
  }
}
