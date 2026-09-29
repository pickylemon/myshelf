package com.myshelf.auth.adapter.in.web;

import com.myshelf.auth.application.port.in.LoginCommand;
import com.myshelf.auth.application.port.in.LoginUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class LoginController {
  private final LoginUseCase loginUseCase;

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest loginRequest) {
    LoginCommand loginCommand = loginRequest.toCommand();
    String accessToken = loginUseCase.login(loginCommand);
    return ResponseEntity.ok(new LoginResponse(accessToken));
  }
}
