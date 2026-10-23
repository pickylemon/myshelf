package com.myshelf.auth.adapter.in.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  private final BearerTokenAuthenticationEntryPoint delegate =
      new BearerTokenAuthenticationEntryPoint();

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {

    delegate.commence(request, response, authException);

    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatusCode.valueOf(response.getStatus()), "인증 정보가 없거나 유효하지 않습니다.");

    problemDetail.setTitle("Auth Error");
    problemDetail.setProperty("code", "AUTHENTICATION_FAILED");

    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), problemDetail);
  }
}
