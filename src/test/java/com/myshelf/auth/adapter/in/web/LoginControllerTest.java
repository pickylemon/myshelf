package com.myshelf.auth.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.myshelf.auth.application.port.in.LoginCommand;
import com.myshelf.auth.application.port.in.LoginUseCase;
import com.myshelf.auth.error.AuthErrorCode;
import com.myshelf.auth.exception.AuthException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * LoginController의 HTTP 요청 처리 책임을 검증한다.
 *
 * <p>유효한 요청의 UseCase 전달, request validation, AuthException의 HTTP 응답 변환을 확인한다.
 *
 * <p>Security 동작은 LoginIntegrationTest에서 별도로 검증하므로 이 테스트에서는 Security Filter를 적용하지 않는다.
 */
@WebMvcTest(LoginController.class)
@AutoConfigureMockMvc(addFilters = false)
class LoginControllerTest {

  @Autowired MockMvc mockMvc;

  @Autowired ObjectMapper objectMapper;

  @MockitoBean LoginUseCase loginUseCase;

  /** 유효한 요청은 올바른 Command로 변환되어 UseCase에 전달되어야 하고, UseCase가 반환한 토큰이 그대로 200 응답 body로 나가야 한다. */
  @Test
  void validRequestReachesUseCaseAndReturnsAccessToken() throws Exception {
    String issuedToken = "issued-access-token";
    when(loginUseCase.login(any())).thenReturn(issuedToken);

    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new LoginRequestBody("user@example.com", "raw-password"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value(issuedToken));

    verify(loginUseCase).login(new LoginCommand("user@example.com", "raw-password"));
  }

  /**
   * email/password validation 규칙을 위반한 요청은 UseCase를 호출하지 않고 400을 반환해야 한다.
   *
   * <p>validation 제약이 LoginCommand가 아니라 LoginRequest에 실제로 붙어 있어야 @Valid가 동작한다 — 이 테스트가 그 회귀(제약이 잘못된
   * 위치에 있어 검증이 전혀 동작하지 않는 문제)를 잡아낸다.
   */
  @ParameterizedTest(name = "[{index}] email={0}, password={1} -> 400")
  @MethodSource("invalidLoginRequests")
  void invalidRequestReturnsBadRequestWithoutCallingUseCase(String email, String password)
      throws Exception {
    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequestBody(email, password))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(loginUseCase);
  }

  /** UseCase가 INVALID_CREDENTIALS 예외를 던지면 ControllerAdvice가 401 + ProblemDetail(code)로 응답해야 한다. */
  @Test
  void invalidCredentialsFromUseCaseReturnsUnauthorizedProblemDetail() throws Exception {
    when(loginUseCase.login(any())).thenThrow(new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new LoginRequestBody("user@example.com", "raw-password"))))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
  }

  private static Stream<Arguments> invalidLoginRequests() {
    return Stream.of(
        Arguments.of("", "raw-password"), // 이메일 blank
        Arguments.of("not-an-email", "raw-password"), // 이메일 형식 오류
        Arguments.of("user@example.com", "") // 비밀번호 blank
        );
  }

  private record LoginRequestBody(String email, String password) {}
}
