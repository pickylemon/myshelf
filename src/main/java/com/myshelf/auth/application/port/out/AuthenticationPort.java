package com.myshelf.auth.application.port.out;

public interface AuthenticationPort {
  AuthenticatedUser authenticate(String email, String password);
}
