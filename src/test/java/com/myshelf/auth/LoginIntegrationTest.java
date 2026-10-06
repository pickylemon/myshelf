package com.myshelf.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.myshelf.user.adapter.out.persistence.UserPersistenceAdapter;
import com.myshelf.user.domain.User;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인 기능이 실제 애플리케이션의 Security, Controller, Service, Persistence 및 Flyway로 구성된 DB를 함께 통과해 동작하는지
 * 검증한다.
 *
 * <p>local profile을 활성화하여 일반 API용 SecurityFilterChain과 H2 Console 전용 SecurityFilterChain이 함께 존재하는
 * 환경에서도 POST /auth/login이 올바른 Security 설정을 통해 처리되는지 확인한다. test profile은
 * src/test/resources/application-test.yaml의 테스트 전용 jwt.secret을 적용해 JWT_SECRET 환경변수 없이도 실행되게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class LoginIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Autowired ObjectMapper objectMapper;

  @Autowired UserPersistenceAdapter userPersistenceAdapter;

  @Autowired PasswordEncoder passwordEncoder;

  /** 회원가입 API를 거치지 않고 로그인에 필요한 사용자를 Persistence Adapter로 직접 저장한다. 이 테스트의 대상은 로그인 흐름이다. */
  private String registerUser(String rawPassword) {
    String email = "login-" + UUID.randomUUID() + "@example.com";
    userPersistenceAdapter.save(
        new User(UUID.randomUUID(), email, passwordEncoder.encode(rawPassword)));
    return email;
  }

  /**
   * 인증 토큰이나 CSRF 토큰 없이도 등록된 사용자가 올바른 비밀번호로 로그인하면 200과 accessToken을 응답받아야 한다. 이 요청이 성공한다는 것 자체가 POST
   * /auth/login이 인증 없이 접근 가능함을 함께 증명한다.
   */
  @Test
  void loginWithValidCredentialsReturnsAccessTokenWithoutAuthentication() throws Exception {
    String email = registerUser("Abcdefg1!");

    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequestBody(email, "Abcdefg1!"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty());
  }

  /**
   * 존재하지 않는 이메일로 로그인한 경우와, 존재하는 이메일에 틀린 비밀번호로 로그인한 경우 모두 401 + INVALID_CREDENTIALS로 응답해야 하고, 두 응답이
   * 외부에서 구분되지 않아야 한다(이메일 존재 여부 비노출).
   */
  @Test
  void loginWithNonExistentEmailAndWrongPasswordReturnIndistinguishableUnauthorized()
      throws Exception {
    String registeredEmail = registerUser("Abcdefg1!");
    String nonExistentEmail = "missing-" + UUID.randomUUID() + "@example.com";

    String nonExistentEmailResponse =
        mockMvc
            .perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new LoginRequestBody(nonExistentEmail, "Abcdefg1!"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String wrongPasswordResponse =
        mockMvc
            .perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new LoginRequestBody(registeredEmail, "WrongPassword1!"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(nonExistentEmailResponse).isEqualTo(wrongPasswordResponse);
  }

  private record LoginRequestBody(String email, String password) {}
}
