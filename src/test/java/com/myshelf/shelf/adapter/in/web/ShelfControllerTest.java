package com.myshelf.shelf.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.auth.adapter.in.security.ProblemDetailAuthenticationEntryPoint;
import com.myshelf.global.config.SecurityConfig;
import com.myshelf.shelf.application.port.in.ShelfCreateCommand;
import com.myshelf.shelf.application.port.in.ShelfCreateUseCase;
import com.myshelf.shelf.application.port.in.ShelfDeleteUseCase;
import com.myshelf.shelf.application.port.in.ShelfGetUseCase;
import com.myshelf.shelf.application.port.in.ShelfListUseCase;
import com.myshelf.shelf.application.port.in.ShelfUpdateCommand;
import com.myshelf.shelf.application.port.in.ShelfUpdateUseCase;
import com.myshelf.shelf.domain.Shelf;
import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
  @MockitoBean ShelfUpdateUseCase shelfUpdateUseCase;
  @MockitoBean ShelfDeleteUseCase shelfDeleteUseCase;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean AuthenticationManager authenticationManager;

  /** 각 엔드포인트의 미인증 요청을 Controller·UseCase에 도달하기 전에 거부한다. JWT 오류 종류는 반복하지 않는다. */
  @ParameterizedTest
  @MethodSource("인증이_필요한_요청")
  void 토큰이_없으면_UseCase_호출_전에_401로_거부한다(HttpMethod method, String path) throws Exception {
    MockHttpServletRequestBuilder builder = request(method, path);
    if (method == HttpMethod.POST || method == HttpMethod.PUT) {
      builder
          .contentType(MediaType.APPLICATION_JSON)
          .content(
              """
              {"name":"내 서재", "description":"설명"}
              """);
    }

    mockMvc
        .perform(builder)
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
        .andExpect(result -> assertThat(result.getHandler()).isNull());

    verifyNoInteractions(
        shelfCreateUseCase,
        shelfListUseCase,
        shelfGetUseCase,
        shelfUpdateUseCase,
        shelfDeleteUseCase,
        jwtDecoder);
  }

  /** Shelf 이름이 null·빈 문자열·공백이거나 이름 또는 설명이 256자이면 UseCase 호출 없이 400을 반환한다. */
  @ParameterizedTest
  @MethodSource("잘못된_생성_요청")
  void 생성_입력이_잘못되면_UseCase_호출_없이_400을_반환한다(String name, String description) throws Exception {
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
  @MethodSource("유효한_생성_요청")
  void 생성_요청을_전달하고_생성된_ID를_201로_반환한다(String name, String description) throws Exception {
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
  void 생성_설명을_생략하면_null로_전달한다() throws Exception {
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
  void 이름_중복_예외를_409_ProblemDetail로_반환한다() throws Exception {
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
  void 소유자_부재_예외를_Bearer_헤더와_401로_반환한다() throws Exception {
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

  /** 목록의 각 도메인 객체를 응답 DTO로 변환하고 인증 사용자 UUID를 UseCase에 전달한다. */
  @Test
  void 인증_사용자를_전달하고_서재_목록을_응답으로_변환한다() throws Exception {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-10-01T01:02:03Z");
    Instant updatedAt = Instant.parse("2026-10-02T04:05:06Z");
    when(shelfListUseCase.list(USER_ID))
        .thenReturn(
            List.of(
                new Shelf(firstId, "소설", "읽을 책", USER_ID, createdAt, updatedAt),
                new Shelf(secondId, "인문", null, USER_ID, createdAt, updatedAt)));

    mockMvc
        .perform(get("/shelves").with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(
            content()
                .json(
                    """
                    [
                      {"id":"%s", "name":"소설", "description":"읽을 책",
                       "createdAt":"2026-10-01T01:02:03Z", "updatedAt":"2026-10-02T04:05:06Z"},
                      {"id":"%s", "name":"인문", "description":null,
                       "createdAt":"2026-10-01T01:02:03Z", "updatedAt":"2026-10-02T04:05:06Z"}
                    ]
                    """
                        .formatted(firstId, secondId)))
        .andExpect(jsonPath("$[*].userId").isEmpty());

    verify(shelfListUseCase).list(USER_ID);
  }

  /** 빈 조회 결과는 404가 아닌 200과 빈 JSON 배열로 응답한다. */
  @Test
  void 서재_목록이_없으면_200과_빈_배열을_반환한다() throws Exception {
    when(shelfListUseCase.list(USER_ID)).thenReturn(List.of());

    mockMvc
        .perform(get("/shelves").with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("[]"));

    verify(shelfListUseCase).list(USER_ID);
  }

  /** 단건 응답의 필드·ISO 날짜와 선택 설명을 검증한다. JWT 자체의 검증은 통합 테스트가 담당한다. */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = "주말에 읽을 책")
  void 사용자와_서재_ID를_전달하고_단건_응답을_변환한다(String description) throws Exception {
    UUID shelfId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-10-01T01:02:03Z");
    Instant updatedAt = Instant.parse("2026-10-02T04:05:06Z");
    when(shelfGetUseCase.get(USER_ID, shelfId))
        .thenReturn(new Shelf(shelfId, "내 서재", description, USER_ID, createdAt, updatedAt));

    mockMvc
        .perform(
            get("/shelves/{shelfId}", shelfId)
                .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id").value(shelfId.toString()))
        .andExpect(jsonPath("$.name").value("내 서재"))
        .andExpect(jsonPath("$.description").hasJsonPath())
        .andExpect(
            jsonPath("$.description").value(description == null ? nullValue() : is(description)))
        .andExpect(jsonPath("$.createdAt").value("2026-10-01T01:02:03Z"))
        .andExpect(jsonPath("$.updatedAt").value("2026-10-02T04:05:06Z"))
        .andExpect(jsonPath("$.userId").doesNotExist());

    verify(shelfGetUseCase).get(USER_ID, shelfId);
  }

  /** UseCase가 던진 조회 부재 예외는 404 ProblemDetail로 변환한다. */
  @Test
  void 서재_부재_예외를_404_ProblemDetail로_반환한다() throws Exception {
    UUID shelfId = UUID.randomUUID();
    when(shelfGetUseCase.get(USER_ID, shelfId))
        .thenThrow(new ShelfException(ShelfErrorCode.SHELF_NOT_FOUND));

    mockMvc
        .perform(
            get("/shelves/{shelfId}", shelfId)
                .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Shelf Error"))
        .andExpect(jsonPath("$.detail").value(ShelfErrorCode.SHELF_NOT_FOUND.getMessage()))
        .andExpect(jsonPath("$.code").value("SHELF_NOT_FOUND"))
        .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
  }

  /** 잘못된 UUID는 UseCase를 호출하기 전에 400으로 거부한다. */
  @Test
  void 단건_조회_ID가_잘못되면_UseCase_호출_없이_400을_반환한다() throws Exception {
    mockMvc
        .perform(
            get("/shelves/not-a-uuid").with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(shelfGetUseCase, shelfListUseCase);
  }

  /** 수정 요청의 JWT 사용자·경로 ID·본문을 Command로 전달하고 UseCase 결과를 200 응답으로 변환한다. */
  @Test
  void 수정_요청을_전달하고_수정된_서재를_200으로_반환한다() throws Exception {
    UUID shelfId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-10-01T01:02:03Z");
    Instant updatedAt = Instant.parse("2026-10-02T04:05:06Z");
    ShelfUpdateCommand command = new ShelfUpdateCommand(USER_ID, shelfId, "새 이름", "새 설명");
    when(shelfUpdateUseCase.update(command))
        .thenReturn(new Shelf(shelfId, "새 이름", "새 설명", USER_ID, createdAt, updatedAt));

    mockMvc
        .perform(
            put("/shelves/{shelfId}", shelfId)
                .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateRequestBody("새 이름", "새 설명"))))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id").value(shelfId.toString()))
        .andExpect(jsonPath("$.name").value("새 이름"))
        .andExpect(jsonPath("$.description").value("새 설명"))
        .andExpect(jsonPath("$.createdAt").value("2026-10-01T01:02:03Z"))
        .andExpect(jsonPath("$.updatedAt").value("2026-10-02T04:05:06Z"))
        .andExpect(jsonPath("$.userId").doesNotExist());

    verify(shelfUpdateUseCase).update(command);
  }

  /** 별도 수정 DTO에도 이름 필수·길이 제한과 설명 길이 제한이 연결되어 있는지 확인한다. */
  @ParameterizedTest
  @MethodSource("잘못된_수정_요청")
  void 수정_입력이_잘못되면_UseCase_호출_없이_400을_반환한다(String name, String description) throws Exception {
    mockMvc
        .perform(
            put("/shelves/{shelfId}", UUID.randomUUID())
                .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateRequestBody(name, description))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(shelfUpdateUseCase);
  }

  /** 삭제는 경로 Shelf ID와 JWT 사용자 ID를 전달하고 본문 없는 204로 응답한다. */
  @Test
  void 삭제_요청을_전달하고_본문_없이_204를_반환한다() throws Exception {
    UUID shelfId = UUID.randomUUID();

    mockMvc
        .perform(
            delete("/shelves/{shelfId}", shelfId)
                .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    verify(shelfDeleteUseCase).delete(shelfId, USER_ID);
  }

  private static Stream<Arguments> 인증이_필요한_요청() {
    String detailPath = "/shelves/00000000-0000-0000-0000-000000000001";
    return Stream.of(
        Arguments.of(HttpMethod.POST, "/shelves"),
        Arguments.of(HttpMethod.GET, "/shelves"),
        Arguments.of(HttpMethod.GET, detailPath),
        Arguments.of(HttpMethod.PUT, detailPath),
        Arguments.of(HttpMethod.DELETE, detailPath));
  }

  private static Stream<Arguments> 잘못된_수정_요청() {
    return Stream.of(
        Arguments.of(" ", "설명"),
        Arguments.of("이".repeat(256), null),
        Arguments.of("내 서재", "설".repeat(256)));
  }

  private static Stream<Arguments> 잘못된_생성_요청() {
    return Stream.of(
        Arguments.of(null, null),
        Arguments.of("", null),
        Arguments.of(" \t\n", null),
        Arguments.of("이".repeat(256), null),
        Arguments.of("내 서재", "설".repeat(256)));
  }

  private static Stream<Arguments> 유효한_생성_요청() {
    return Stream.of(
        Arguments.of("내 서재", "책과 함께하는 주말"),
        Arguments.of("이".repeat(255), "설".repeat(255)),
        Arguments.of("내 서재", null),
        Arguments.of("내 서재", ""));
  }

  private record CreateRequestBody(String name, String description) {}

  private record UpdateRequestBody(String name, String description) {}
}
