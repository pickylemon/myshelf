package com.myshelf.user.application.port.in;

import java.util.UUID;

public interface UserSignUpUseCase {
  UUID signUp(UserSignUpCommand command);
}
