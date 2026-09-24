package com.myshelf.user.adapter.in.web;

import com.myshelf.user.application.port.in.UserSignUpCommand;
import com.myshelf.user.application.port.in.UserSignUpUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {
  private final UserSignUpUseCase userSignUpUseCase;

  @PostMapping
  public ResponseEntity<UUID> createUser(@RequestBody @Valid UserSignUpRequest userSignUpRequest) {
    UserSignUpCommand userSignUpCommand = userSignUpRequest.toCommand();
    UUID uuid = userSignUpUseCase.signUp(userSignUpCommand);
    return ResponseEntity.status(HttpStatus.CREATED).body(uuid);
  }
}
