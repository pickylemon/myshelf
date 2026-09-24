package com.myshelf.user.adapter.in.web;

import com.myshelf.user.application.port.in.UserSignUpCommand;
import jakarta.validation.constraints.*;

public record UserSignUpRequest(
    @Email @NotBlank String email,
    @NotBlank
        @Size(min = 8, max = 64)
        @Pattern(
            regexp = "^[A-Za-z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]+$",
            message = "비밀번호는 영문, 숫자, 특수문자만 사용할 수 있습니다.")
        String password) {
  public UserSignUpCommand toCommand() {
    return new UserSignUpCommand(this.email, this.password);
  }
}
