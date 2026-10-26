package com.myshelf.shelf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.auth.application.port.out.AccessTokenIssuerPort;
import com.myshelf.user.adapter.out.persistence.UserPersistenceAdapter;
import com.myshelf.user.domain.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** 실제 JWT의 사용자 UUID가 Shelf 소유자로 저장되는 흐름과 Shelf 이름 중복·소유자 부재 응답을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShelfCreateIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AccessTokenIssuerPort accessTokenIssuerPort;
  @Autowired UserPersistenceAdapter userPersistenceAdapter;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired JwtDecoder jwtDecoder;

  private final List<UUID> userIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (UUID userId : userIds) {
      jdbcTemplate.update("delete from shelves where user_id = ?", userId);
      jdbcTemplate.update("delete from users where id = ?", userId);
    }
  }

  /** 정상 요청은 201과 Shelf ID를 반환하고, 해당 DB 행에 JWT 사용자 UUID·Shelf 이름·설명을 저장한다. */
  @ParameterizedTest
  @MethodSource("validRequests")
  void createPersistsShelfWithJwtOwner(String name, String description) throws Exception {
    UUID userId = createUser();
    String token = accessTokenIssuerPort.issue(userId);

    UUID shelfId = createShelf(token, name, description);

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "select id, user_id, name, description from shelves where id = ?", shelfId);
    assertThat(row.get("id")).isEqualTo(shelfId);
    assertThat(row.get("user_id")).isEqualTo(userId);
    assertThat(row.get("name")).isEqualTo(name);
    assertThat(row.get("description")).isEqualTo(description);
  }

  /** 같은 사용자가 동일한 Shelf 이름으로 다시 생성하면 409를 반환하고 기존 Shelf 한 개와 설명을 유지한다. */
  @Test
  void sameOwnerAndNameReturnsConflictWithoutCreatingAnotherShelf() throws Exception {
    UUID userId = createUser();
    String token = accessTokenIssuerPort.issue(userId);
    UUID firstShelf = createShelf(token, "읽고 싶은 책", "처음 설명");

    mockMvc
        .perform(
            post("/shelves")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new CreateRequestBody("읽고 싶은 책", "두 번째 설명"))))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("DUPLICATE_NAME"));

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from shelves where user_id = ? and name = ?",
                Long.class,
                userId,
                "읽고 싶은 책"))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select description from shelves where id = ?", String.class, firstShelf))
        .isEqualTo("처음 설명");
  }

  /** 서로 다른 사용자는 같은 Shelf 이름으로 생성할 수 있고, 각 Shelf에는 해당 사용자 UUID가 저장된다. */
  @Test
  void differentOwnersCanCreateShelvesWithSameName() throws Exception {
    UUID firstUser = createUser();
    UUID secondUser = createUser();
    UUID firstShelf = createShelf(accessTokenIssuerPort.issue(firstUser), "같은 이름", null);
    UUID secondShelf = createShelf(accessTokenIssuerPort.issue(secondUser), "같은 이름", null);

    assertThat(firstShelf).isNotEqualTo(secondShelf);
    assertThat(
            jdbcTemplate.queryForObject(
                "select user_id from shelves where id = ?", UUID.class, firstShelf))
        .isEqualTo(firstUser);
    assertThat(
            jdbcTemplate.queryForObject(
                "select user_id from shelves where id = ?", UUID.class, secondShelf))
        .isEqualTo(secondUser);
  }

  /** JWT 발급 후 사용자가 삭제되면 토큰 검증은 통과해도 Shelf 생성은 401·Bearer 헤더로 거부되고 저장되지 않는다. */
  @Test
  void validJwtForDeletedUserReturnsUnauthorizedWithoutCreatingShelf() throws Exception {
    UUID userId = createUser();
    String token = accessTokenIssuerPort.issue(userId);
    assertThat(jdbcTemplate.update("delete from users where id = ?", userId)).isEqualTo(1);

    // 사용자 삭제 후에도 JWT 검증은 성공한다. 이후 실패 원인은 Shelf 소유자 부재다.
    assertThat(jwtDecoder.decode(token).getSubject()).isEqualTo(userId.toString());
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from users where id = ?", Long.class, userId))
        .isZero();

    mockMvc
        .perform(
            post("/shelves")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequestBody("내 서재", "설명"))))
        .andExpectAll(
            status().isUnauthorized(),
            header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"),
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
            jsonPath("$.status").value(401),
            jsonPath("$.title").value("Shelf Error"),
            jsonPath("$.code").value("INVALID_SHELF_OWNER"));

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from shelves where user_id = ?", Long.class, userId))
        .isZero();
  }

  private UUID createShelf(String token, String name, String description) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/shelves")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(new CreateRequestBody(name, description))))
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID shelfId = objectMapper.readValue(response, UUID.class);
    assertThat(shelfId).isNotNull();
    return shelfId;
  }

  private UUID createUser() {
    UUID userId = UUID.randomUUID();
    userIds.add(userId);
    userPersistenceAdapter.save(
        new User(userId, "shelf-api-" + userId + "@example.com", "encoded-password"));
    return userId;
  }

  private static Stream<Arguments> validRequests() {
    return Stream.of(
        Arguments.of("읽고 싶은 책", "주말에 읽을 책"),
        Arguments.of("이".repeat(255), "설".repeat(255)),
        Arguments.of("설명 없는 서재", null));
  }

  private record CreateRequestBody(String name, String description) {}
}
