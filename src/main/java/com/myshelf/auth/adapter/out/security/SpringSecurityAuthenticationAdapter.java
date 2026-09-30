package com.myshelf.auth.adapter.out.security;

import com.myshelf.auth.application.port.out.AuthenticatedUser;
import com.myshelf.auth.application.port.out.AuthenticationPort;
import com.myshelf.auth.error.AuthErrorCode;
import com.myshelf.auth.exception.AuthException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringSecurityAuthenticationAdapter implements AuthenticationPort {
  private final AuthenticationManager authenticationManager;

  @Override
  public AuthenticatedUser authenticate(String email, String password) {
    Authentication authenticationRequest =
        UsernamePasswordAuthenticationToken.unauthenticated(email, password);

    Authentication authenticationResult;
    try {
      authenticationResult = authenticationManager.authenticate(authenticationRequest);
    } catch (BadCredentialsException e) {
      throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
    }

    AuthUserPrincipal principal = (AuthUserPrincipal) authenticationResult.getPrincipal();

    return new AuthenticatedUser(principal.getUserId());
  }
}
