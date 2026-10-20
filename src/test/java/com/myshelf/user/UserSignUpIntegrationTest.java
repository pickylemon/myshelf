package com.myshelf.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * signUp 기능이 실제 애플리케이션의 Security, Controller, Service, Persistence 및 Flyway로 구성된 DB를 함께 통과해 동작하는지
 * 검증한다.
 *
 * <p>test 프로필의 PostgreSQL DB를 사용하여 POST /users가 일반 API용 Security 설정을 통해 처리되는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserSignUpIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Autowired ObjectMapper objectMapper;

  /** 정상적인 이메일/비밀번호로 가입하면, 인증 토큰이나 CSRF 토큰 없이도 201과 생성된 사용자 ID(UUID)를 응답받아야 한다. */
  @Test
  void signUpWithValidRequestReturnsCreatedWithUserId() throws Exception {
    String requestBody =
        objectMapper.writeValueAsString(new SignUpRequestBody(uniqueEmail(), "Abcdefg1!"));

    String responseBody =
        mockMvc
            .perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(requestBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID userId = objectMapper.readValue(responseBody, UUID.class);

    assertThat(userId).isNotNull();
  }

  /** 이미 가입된 이메일로 다시 가입을 시도하면 409와 DUPLICATE_EMAIL 코드를 응답받아야 한다. */
  @Test
  void signUpWithAlreadyRegisteredEmailReturnsConflict() throws Exception {
    String requestBody =
        objectMapper.writeValueAsString(new SignUpRequestBody(uniqueEmail(), "Abcdefg1!"));

    mockMvc
        .perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(requestBody))
        .andExpect(status().isCreated());

    mockMvc
        .perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(requestBody))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
  }

  private static String uniqueEmail() {
    return "integration-" + UUID.randomUUID() + "@example.com";
  }

  private record SignUpRequestBody(String email, String password) {}
}
