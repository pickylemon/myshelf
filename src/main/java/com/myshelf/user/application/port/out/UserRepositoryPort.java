package com.myshelf.user.application.port.out;

import com.myshelf.user.domain.User;

public interface UserRepositoryPort {
  User save(User user);

  boolean existsByEmail(String email);
}
