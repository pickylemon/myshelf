package com.myshelf.auth.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** HTTP 요청의 Command 변환, validation 및 인증 실패 응답 계약을 검증한다. */
@WebMvcTest(LoginController.class)
@AutoConfigureMockMvc(addFilters = false)
class LoginControllerTest {

  @Autowired MockMvc mockMvc;

  @Autowired ObjectMapper objectMapper;

  @MockitoBean LoginUseCase loginUseCase;

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
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
  }

  // @NotBlank와 @Email validation 실패 케이스.
  private static Stream<Arguments> invalidLoginRequests() {
    return Stream.of(
        Arguments.of(null, "raw-password"),
        Arguments.of("", "raw-password"),
        Arguments.of("   ", "raw-password"),
        Arguments.of("not-an-email", "raw-password"),
        Arguments.of("user@example.com", null),
        Arguments.of("user@example.com", ""),
        Arguments.of("user@example.com", "   "));
  }

  private record LoginRequestBody(String email, String password) {}
}
