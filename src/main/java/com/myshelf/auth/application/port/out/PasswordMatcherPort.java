package com.myshelf.auth.application.port.out;

public interface PasswordMatcherPort {
  boolean matches(String rawPassword, String encodedPassword);
}
