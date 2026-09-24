package com.myshelf.user.domain;

import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class User {
  private final UUID id;
  private final String email;
  private final String password;
}
