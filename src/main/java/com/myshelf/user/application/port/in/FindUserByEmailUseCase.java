package com.myshelf.user.application.port.in;

import com.myshelf.user.domain.User;
import java.util.Optional;

public interface FindUserByEmailUseCase {
  Optional<User> findByEmail(String email);
}
