package com.myshelf.auth.application.port.in;

public interface LoginUseCase {
  String login(LoginCommand loginCommand);
}
