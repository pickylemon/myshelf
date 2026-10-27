package com.myshelf.shelf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 JWT의 사용자 UUID로 수정·삭제하고, HTTP 결과와 PostgreSQL의 변경·보존을 함께 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShelfUpdateDeleteIntegrationTest {

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

  /** 이름 유지·변경 모두 실제 JWT로 수정되며, 200 응답의 필드·시간이 commit된 DB 값과 일치한다. */
  @ParameterizedTest
  @ValueSource(strings = {"기존 이름", "새 이름"})
  void 본인_서재를_수정하면_200_응답과_DB에_반영된다(String name) throws Exception {
    // given
    UUID userId = createUser();
    Shelf shelf = createShelf(userId, "기존 이름", "기존 설명");
    Map<String, Object> before =
        jdbcTemplate.queryForMap("select * from shelves where id = ?", shelf.getId());

    // when, then
    String response =
        mockMvc
            .perform(
                put("/shelves/{shelfId}", shelf.getId())
                    .header(HttpHeaders.AUTHORIZATION, bearerToken(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new UpdateRequestBody(name, "새 설명"))))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(shelf.getId().toString()))
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.description").value("새 설명"))
            .andExpect(jsonPath("$.userId").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Map<String, Object> stored =
        jdbcTemplate.queryForMap("select * from shelves where id = ?", shelf.getId());
    assertThat(stored.get("user_id")).isEqualTo(userId);
    assertThat(stored.get("name")).isEqualTo(name);
    assertThat(stored.get("description")).isEqualTo("새 설명");
    assertThat(stored.get("created_at")).isEqualTo(before.get("created_at"));

    JsonNode actual = objectMapper.readTree(response);
    assertThat(Instant.parse(actual.get("createdAt").asString()))
        .isEqualTo(((Timestamp) stored.get("created_at")).toInstant());
    assertThat(Instant.parse(actual.get("updatedAt").asString()))
        .isEqualTo(((Timestamp) stored.get("updated_at")).toInstant());
  }

  /** Service의 실제 중복 검사부터 409 응답까지 연결되며, 실패한 수정은 DB에 반영되지 않는다. */
  @Test
  void 다른_서재의_이름으로_수정하면_409와_원본을_유지한다() throws Exception {
    // given
    UUID userId = createUser();
    createShelf(userId, "소설", "소설 설명");
    Shelf target = createShelf(userId, "인문", "기존 설명");
    List<Map<String, Object>> before =
        jdbcTemplate.queryForList("select * from shelves where user_id = ?", userId);

    // when, then
    mockMvc
        .perform(
            put("/shelves/{shelfId}", target.getId())
                .header(HttpHeaders.AUTHORIZATION, bearerToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new UpdateRequestBody("소설", "반영되면 안 되는 설명"))))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("DUPLICATE_NAME"));

    assertThat(jdbcTemplate.queryForList("select * from shelves where user_id = ?", userId))
        .containsExactlyInAnyOrderElementsOf(before);
  }

  /** 타인의 실제 JWT로 수정·삭제할 수 없고, 요청자와 소유자 양쪽의 데이터가 보존된다. */
  @ParameterizedTest
  @ValueSource(strings = {"PUT", "DELETE"})
  void 타인의_JWT로_수정이나_삭제하면_404와_원본을_유지한다(String method) throws Exception {
    // given
    UUID requesterId = createUser();
    UUID ownerId = createUser();
    Shelf target = createShelf(ownerId, "타인의 서재", "비공개 설명");
    // 요청자에게 같은 이름이 있어도 소유권 확인이 먼저이므로 중복 409가 아닌 404여야 한다.
    createShelf(requesterId, "새 이름", "요청자의 서재");
    List<Map<String, Object>> before =
        jdbcTemplate.queryForList(
            "select * from shelves where user_id in (?, ?)", requesterId, ownerId);
    MockHttpServletRequestBuilder builder =
        request(HttpMethod.valueOf(method), "/shelves/{shelfId}", target.getId())
            .header(HttpHeaders.AUTHORIZATION, bearerToken(requesterId));
    if (method.equals("PUT")) {
      builder
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsString(new UpdateRequestBody("새 이름", "변경할 설명")));
    }

    // when, then
    mockMvc
        .perform(builder)
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("SHELF_NOT_FOUND"));

    assertThat(
            jdbcTemplate.queryForList(
                "select * from shelves where user_id in (?, ?)", requesterId, ownerId))
        .containsExactlyInAnyOrderElementsOf(before);
  }

  /** 본인 JWT로 삭제하면 본문 없는 204를 반환하고, 대상 행만 실제로 제거한다. */
  @Test
  void 본인_서재를_삭제하면_204를_반환하고_DB에서_제거한다() throws Exception {
    // given
    UUID userId = createUser();
    Shelf target = createShelf(userId, "삭제할 서재", "설명");
    Shelf remaining = createShelf(userId, "유지할 서재", "남겨둘 설명");
    Map<String, Object> remainingBefore =
        jdbcTemplate.queryForMap("select * from shelves where id = ?", remaining.getId());

    // when, then
    mockMvc
        .perform(
            delete("/shelves/{shelfId}", target.getId())
                .header(HttpHeaders.AUTHORIZATION, bearerToken(userId)))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from shelves where id = ?", Long.class, target.getId()))
        .isZero();
    assertThat(jdbcTemplate.queryForMap("select * from shelves where id = ?", remaining.getId()))
        .isEqualTo(remainingBefore);
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
        new User(userId, "shelf-update-delete-" + userId + "@example.com", "encoded-password"));
    return userId;
  }

  private record UpdateRequestBody(String name, String description) {}
}
