package com.myshelf.shelf.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.auth.adapter.in.security.ProblemDetailAuthenticationEntryPoint;
import com.myshelf.global.config.SecurityConfig;
import com.myshelf.shelf.application.port.in.ShelfCreateCommand;
import com.myshelf.shelf.application.port.in.ShelfCreateUseCase;
import com.myshelf.shelf.application.port.in.ShelfGetUseCase;
import com.myshelf.shelf.application.port.in.ShelfListUseCase;
import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** 실제 보안 설정의 요청 차단과 Shelf 요청 검증·응답 변환을 확인한다. JWT 검증과 DB 저장은 제외한다. */
@WebMvcTest(ShelfController.class)
@Import({SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class})
class ShelfControllerTest {

  private static final UUID USER_ID = UUID.randomUUID();

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;

  @MockitoBean ShelfCreateUseCase shelfCreateUseCase;
  @MockitoBean ShelfListUseCase shelfListUseCase;
  @MockitoBean ShelfGetUseCase shelfGetUseCase;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean AuthenticationManager authenticationManager;

  /** 토큰 없는 Shelf 생성 요청은 Controller와 UseCase에 도달하지 않고 401을 반환한다. */
  @Test
  void unauthenticatedRequestIsRejectedBeforeController() throws Exception {
    mockMvc
        .perform(
            post("/shelves")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequestBody("내 서재", null))))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
        .andExpect(result -> assertThat(result.getHandler()).isNull());

    verifyNoInteractions(shelfCreateUseCase, jwtDecoder);
  }

  /** Shelf 이름이 null·빈 문자열·공백이거나 이름 또는 설명이 256자이면 UseCase 호출 없이 400을 반환한다. */
  @ParameterizedTest
  @MethodSource("invalidRequests")
  void invalidRequestReturnsBadRequestWithoutCallingUseCase(String name, String description)
      throws Exception {
    mockMvc
        .perform(
            post("/shelves")
                .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequestBody(name, description))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(shelfCreateUseCase);
  }

  /** Shelf 이름·설명의 255자 경계와 선택 설명을 허용하고, Command 전달 및 201의 Shelf ID를 확인한다. */
  @ParameterizedTest
  @MethodSource("validRequests")
  void validRequestReturnsCreatedIdAndPassesCommand(String name, String description)
      throws Exception {
    UUID shelfId = UUID.randomUUID();
    when(shelfCreateUseCase.create(any())).thenReturn(shelfId);

    mockMvc
        .perform(
            post("/shelves")
                .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequestBody(name, description))))
        .andExpect(status().isCreated())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().string(objectMapper.writeValueAsString(shelfId)));

    verify(shelfCreateUseCase).create(new ShelfCreateCommand(USER_ID, name, description));
  }

  /** Shelf 설명을 생략하면 Command에 null을 전달하고 201과 UseCase가 반환한 Shelf ID를 응답한다. */
  @Test
  void omittedDescriptionIsPassedAsNull() throws Exception {
    UUID shelfId = UUID.randomUUID();
    when(shelfCreateUseCase.create(any())).thenReturn(shelfId);

    mockMvc
        .perform(
            post("/shelves")
                .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name": "내 서재"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(content().string(objectMapper.writeValueAsString(shelfId)));

    verify(shelfCreateUseCase).create(new ShelfCreateCommand(USER_ID, "내 서재", null));
  }

  /** Shelf 이름 중복 예외는 WWW-Authenticate 헤더 없는 409 ProblemDetail로 응답한다. */
  @Test
  void duplicateNameReturnsConflictProblemDetail() throws Exception {
    when(shelfCreateUseCase.create(any()))
        .thenThrow(new ShelfException(ShelfErrorCode.DUPLICATE_NAME));

    mockMvc
        .perform(
            post("/shelves")
                .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequestBody("내 서재", null))))
        .andExpect(status().isConflict())
        .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.title").value("Shelf Error"))
        .andExpect(jsonPath("$.code").value("DUPLICATE_NAME"));
  }

  /** Shelf 소유자 부재 예외는 INVALID_SHELF_OWNER 코드의 401 ProblemDetail과 Bearer 헤더로 응답한다. */
  @Test
  void invalidShelfOwnerReturnsUnauthorizedWithBearerHeader() throws Exception {
    when(shelfCreateUseCase.create(any()))
        .thenThrow(new ShelfException(ShelfErrorCode.INVALID_SHELF_OWNER));

    mockMvc
        .perform(
            post("/shelves")
                .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequestBody("내 서재", null))))
        .andExpectAll(
            status().isUnauthorized(),
            header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"),
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
            jsonPath("$.status").value(401),
            jsonPath("$.title").value("Shelf Error"),
            jsonPath("$.code").value("INVALID_SHELF_OWNER"));

    verify(shelfCreateUseCase).create(new ShelfCreateCommand(USER_ID, "내 서재", null));
  }

  private static Stream<Arguments> invalidRequests() {
    return Stream.of(
        Arguments.of(null, null),
        Arguments.of("", null),
        Arguments.of(" \t\n", null),
        Arguments.of("이".repeat(256), null),
        Arguments.of("내 서재", "설".repeat(256)));
  }

  private static Stream<Arguments> validRequests() {
    return Stream.of(
        Arguments.of("내 서재", "책과 함께하는 주말"),
        Arguments.of("이".repeat(255), "설".repeat(255)),
        Arguments.of("내 서재", null),
        Arguments.of("내 서재", ""));
  }

  private record CreateRequestBody(String name, String description) {}
}
