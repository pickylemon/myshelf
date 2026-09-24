package com.myshelf.user.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.myshelf.user.application.port.in.UserSignUpCommand;
import com.myshelf.user.application.port.in.UserSignUpUseCase;
import com.myshelf.user.error.UserErrorCode;
import com.myshelf.user.exception.UserException;
import java.util.UUID;
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
 * UserController의 HTTP 요청 처리 책임을 검증한다.
 *
 * <p>유효한 요청의 UseCase 전달, request validation, UserException의 HTTP 응답 변환을 확인한다.
 *
 * <p>Security 동작은 UserSignUpIntegrationTest에서 별도로 검증하므로 이 테스트에서는 Security Filter를 적용하지 않는다.
 */
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

  @Autowired MockMvc mockMvc;

  @Autowired ObjectMapper objectMapper;

  @MockitoBean UserSignUpUseCase userSignUpUseCase;

  /** 유효한 요청은 올바른 Command로 변환되어 UseCase에 전달되어야 하고, UseCase가 반환한 ID가 그대로 201 응답 body로 나가야 한다. */
  @Test
  void validRequestReachesUseCaseAndReturnsCreated() throws Exception {
    UUID id = UUID.randomUUID();
    when(userSignUpUseCase.signUp(any())).thenReturn(id);

    mockMvc
        .perform(
            post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new SignUpRequestBody("user@example.com", "Abcdefg1!"))))
        .andExpect(status().isCreated())
        .andExpect(content().string("\"" + id + "\""));

    verify(userSignUpUseCase).signUp(new UserSignUpCommand("user@example.com", "Abcdefg1!"));
  }

  /** email/password validation 규칙을 위반한 요청은 UseCase를 호출하지 않고 400을 반환해야 한다. */
  @ParameterizedTest(name = "[{index}] email={0}, password={1} -> 400")
  @MethodSource("invalidSignUpRequests")
  void invalidRequestReturnsBadRequestWithoutCallingUseCase(String email, String password)
      throws Exception {
    mockMvc
        .perform(
            post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignUpRequestBody(email, password))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(userSignUpUseCase);
  }

  /** UseCase가 DUPLICATE_EMAIL 예외를 던지면 ControllerAdvice가 409 + ProblemDetail(code)로 응답해야 한다. */
  @Test
  void duplicateEmailFromUseCaseReturnsConflictProblemDetail() throws Exception {
    when(userSignUpUseCase.signUp(any()))
        .thenThrow(new UserException(UserErrorCode.DUPLICATE_EMAIL));

    mockMvc
        .perform(
            post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new SignUpRequestBody("user@example.com", "Abcdefg1!"))))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
  }

  private static Stream<Arguments> invalidSignUpRequests() {
    String tooLongPassword = "a".repeat(65);
    return Stream.of(
        Arguments.of("", "Abcdefg1!"), // 이메일 blank
        Arguments.of("not-an-email", "Abcdefg1!"), // 이메일 형식 오류
        Arguments.of("user@example.com", ""), // 비밀번호 blank
        Arguments.of("user@example.com", "short1!"), // 비밀번호 7자 (min 8)
        Arguments.of("user@example.com", tooLongPassword), // 비밀번호 65자 (max 64)
        Arguments.of("user@example.com", "비밀번호123!") // 허용되지 않은 문자(한글) 포함
        );
  }

  private record SignUpRequestBody(String email, String password) {}
}
