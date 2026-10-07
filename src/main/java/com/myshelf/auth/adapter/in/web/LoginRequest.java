package com.myshelf.auth.adapter.in.web;

import com.myshelf.auth.application.port.in.LoginCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
  public LoginCommand toCommand() {
    return new LoginCommand(this.email(), this.password());
  }
}
