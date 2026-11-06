package com.myshelf.shelf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.auth.application.port.out.AccessTokenIssuerPort;
import com.myshelf.shelf.adapter.out.persistence.ShelfPersistenceAdapter;
import com.myshelf.shelf.domain.Shelf;
import com.myshelf.user.adapter.out.persistence.UserPersistenceAdapter;
import com.myshelf.user.domain.User;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** 실제 JWT와 PostgreSQL을 통해 Shelf 조회의 사용자 격리·응답 필드·실패 응답을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShelfReadIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AccessTokenIssuerPort accessTokenIssuerPort;
  @Autowired UserPersistenceAdapter userPersistenceAdapter;
  @Autowired ShelfPersistenceAdapter shelfPersistenceAdapter;
  @Autowired JdbcTemplate jdbcTemplate;

  private final List<UUID> userIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (UUID userId : userIds) {
      jdbcTemplate.update("delete from shelves where user_id = ?", userId);
      jdbcTemplate.update("delete from users where id = ?", userId);
    }
  }

  /** 타인의 Shelf가 함께 존재해도 자신의 Shelf만 반환하며, 정렬 순서는 요구하지 않는다. */
  @Test
  void listReturnsOnlyAuthenticatedUsersShelves() throws Exception {
    UUID userId = createUser();
    UUID otherUserId = createUser();
    Shelf first = createShelf(userId, "같은 이름", "내 서재 설명");
    Shelf second = createShelf(userId, "설명 없는 서재", null);
    createShelf(otherUserId, "같은 이름", "다른 사용자의 설명");

    mockMvc
        .perform(get("/shelves").header(HttpHeaders.AUTHORIZATION, bearerToken(userId)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(
            jsonPath("$[*].id")
                .value(containsInAnyOrder(first.id().toString(), second.id().toString())))
        .andExpect(jsonPath("$[*].userId").isEmpty()); // 응답 스펙 검증. userId는 노출하지 않는다.
  }

  /** 다른 사용자에게 Shelf가 있어도 자신의 Shelf가 없으면 200과 빈 배열을 반환한다. */
  @Test
  void listReturnsEmptyArrayWhenUserHasNoShelves() throws Exception {
    UUID userId = createUser();
    createShelf(createUser(), "타인의 서재", null);

    mockMvc
        .perform(get("/shelves").header(HttpHeaders.AUTHORIZATION, bearerToken(userId)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("[]"));
  }

  /** 실제 JWT로 조회한 응답이 PostgreSQL의 이름·설명·생성 및 수정 시각과 일치한다. */
  @Test
  void detailReturnsOwnedShelfWithAllResponseFields() throws Exception {
    UUID userId = createUser();
    Shelf shelf = createShelf(userId, "내 서재", "주말에 읽을 책");
    Map<String, Object> stored =
        jdbcTemplate.queryForMap(
            "select name, description, created_at, updated_at from shelves where id = ?",
            shelf.id());

    String response =
        mockMvc
            .perform(
                get("/shelves/{shelfId}", shelf.id())
                    .header(HttpHeaders.AUTHORIZATION, bearerToken(userId)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(shelf.id().toString()))
            .andExpect(jsonPath("$.name").value(stored.get("name")))
            .andExpect(jsonPath("$.description").value(stored.get("description")))
            .andExpect(jsonPath("$.userId").doesNotExist()) // 응답 스펙 검증
            .andReturn()
            .getResponse()
            .getContentAsString();

    var actual = objectMapper.readTree(response);
    assertThat(Instant.parse(actual.get("createdAt").asString()))
        .isEqualTo(((Timestamp) stored.get("created_at")).toInstant());
    assertThat(Instant.parse(actual.get("updatedAt").asString()))
        .isEqualTo(((Timestamp) stored.get("updated_at")).toInstant());
  }

  /** 타인의 Shelf와 존재하지 않는 Shelf 모두 동일한 404로 처리해 존재 여부를 노출하지 않는다. */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void inaccessibleShelfReturnsNotFound(boolean ownedByOtherUser) throws Exception {
    UUID userId = createUser();
    UUID shelfId =
        ownedByOtherUser ? createShelf(createUser(), "타인의 서재", "비공개 설명").id() : UUID.randomUUID();

    mockMvc
        .perform(
            get("/shelves/{shelfId}", shelfId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(userId)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Shelf Error"))
        .andExpect(jsonPath("$.code").value("SHELF_NOT_FOUND"))
        .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
  }

  /** 목록과 단건 모두 인증되지 않은 요청을 401·Bearer 헤더·인증 오류 코드로 거부한다. */
  @ParameterizedTest
  @ValueSource(strings = {"/shelves", "/shelves/00000000-0000-0000-0000-000000000001"})
  void readWithoutTokenReturnsUnauthorized(String path) throws Exception {
    mockMvc
        .perform(get(path))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
  }

  private String bearerToken(UUID userId) {
    return "Bearer " + accessTokenIssuerPort.issue(userId);
  }

  private Shelf createShelf(UUID userId, String name, String description) {
    return shelfPersistenceAdapter.save(new Shelf(UUID.randomUUID(), name, description, userId));
  }

  private UUID createUser() {
    UUID userId = UUID.randomUUID();
    userIds.add(userId);
    userPersistenceAdapter.save(
        new User(userId, "shelf-read-" + userId + "@example.com", "encoded-password"));
    return userId;
  }
}
