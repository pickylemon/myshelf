package com.myshelf.user.application.port.out;

public interface PasswordEncoderPort {
  String encode(String password);

  boolean matches(String rawpassword, String encodedpassword);
}
