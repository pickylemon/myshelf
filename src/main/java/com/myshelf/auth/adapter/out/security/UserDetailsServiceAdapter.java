package com.myshelf.auth.adapter.out.security;

import com.myshelf.user.application.port.in.FindUserByEmailUseCase;
import com.myshelf.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserDetailsServiceAdapter implements UserDetailsService {
  private final FindUserByEmailUseCase findUserByEmailUseCase;

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    User user =
        findUserByEmailUseCase
            .findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found"));

    return new AuthUserPrincipal(user.getId(), user.getEmail(), user.getPassword());
  }
}
