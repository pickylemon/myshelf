package com.myshelf.auth.adapter.out.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@AllArgsConstructor
public class AuthUserPrincipal implements UserDetails, CredentialsContainer {
  @Getter private final UUID userId;
  @Getter private final String email;
  private @Nullable String password;

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of();
  }

  @Override
  public @Nullable String getPassword() {
    return password;
  }

  @Override
  public String getUsername() {
    return email;
  }

  @Override
  public void eraseCredentials() {
    password = null;
  }
}
