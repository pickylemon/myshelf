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

/** test 프로필의 PostgreSQL DB에서 로그인 흐름과 인증 실패 응답의 비노출 특성을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Autowired ObjectMapper objectMapper;

  @Autowired UserPersistenceAdapter userPersistenceAdapter;

  @Autowired PasswordEncoder passwordEncoder;

  private String registerUser(String rawPassword) {
    String email = "login-" + UUID.randomUUID() + "@example.com";
    userPersistenceAdapter.save(
        new User(UUID.randomUUID(), email, passwordEncoder.encode(rawPassword)));
    return email;
  }

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

  @Test
  void loginWithNonExistentEmailAndWrongPasswordReturnIndistinguishableUnauthorized()
      throws Exception {
    // given
    String rawPassword = "Abcdefg1!";
    String registeredEmail = registerUser(rawPassword);
    String nonExistentEmail = "missing-" + UUID.randomUUID() + "@example.com";

    // when
    String nonExistentEmailResponse =
        mockMvc
            .perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new LoginRequestBody(nonExistentEmail, rawPassword))))
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

    // then
    assertThat(objectMapper.readTree(nonExistentEmailResponse))
        .isEqualTo(objectMapper.readTree(wrongPasswordResponse));
  }

  // 로그인 API의 JSON 계약만 표현하고 프로덕션 LoginRequest DTO에는 의존하지 않는다.
  private record LoginRequestBody(String email, String password) {}
}
