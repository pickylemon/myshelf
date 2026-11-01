package com.myshelf.book.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.model.BookSearchState;
import com.myshelf.book.application.port.out.BookSearchField;
import com.myshelf.global.config.RedisConfig;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 실제 Redis DB 1에서 검색 상태의 저장·복원·삭제·만료를 검증한다. PostgreSQL·외부 API는 사용하지 않는다. */
@SpringBootTest(classes = RedisBookSearchStateStoreIntegrationTest.RedisTestConfig.class)
@ActiveProfiles({"test", "redis"})
class RedisBookSearchStateStoreIntegrationTest {

  @Autowired RedisBookSearchStateStore store;
  @Autowired DataRedisProperties redisProperties;
  @Autowired RedisTemplate<String, BookSearchState> redisTemplate;

  private final List<UUID> searchIds = new ArrayList<>();

  @BeforeEach
  void 테스트용_Redis_DB를_사용하는지_확인한다() {
    assertThat(redisProperties.getDatabase()).isEqualTo(1);
  }

  @AfterEach
  void cleanUp() {
    for (UUID searchId : searchIds) {
      store.delete(searchId);
    }
  }

  /** 도서 record·LocalDate·List·Set·Enum 키 Map이 저장 전과 같은 값으로 복원된다. */
  @Test
  void 저장한_검색_상태를_모든_필드와_함께_복원한다() {
    // given
    UUID searchId = UUID.randomUUID();
    BookSearchState expected =
        new BookSearchState(
            UUID.randomUUID(),
            "자바",
            List.of(
                new BookSearchMeta(
                    "9781234567897",
                    "첫 번째 도서",
                    "저자 A",
                    "출판사 A",
                    LocalDate.of(2026, 10, 1),
                    "https://test.test.test.png"),
                new BookSearchMeta("9781234567880", "두 번째 도서", "저자 B", "출판사 B", null, null)),
            // 이미 반환한 도서와 아직 반환하지 않은 도서의 ISBN을 모두 보관한다.
            Set.of("9781234567897", "9781234567880", "9781234567873"),
            Map.of(BookSearchField.TITLE, 2, BookSearchField.AUTHOR, 3));
    searchIds.add(searchId);

    // when
    store.save(searchId, expected);

    // then
    assertThat(store.findById(searchId))
        .hasValueSatisfying(
            actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(expected));
  }

  /** 같은 검색 ID로 저장하면 기존 상태 대신 새 상태가 복원된다. */
  @Test
  void 같은_검색_ID에_저장하면_기존_상태를_덮어쓴다() {
    // given
    UUID searchId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    searchIds.add(searchId);
    BookSearchState original =
        new BookSearchState(userId, "자바", List.of(), Set.of(), Map.of(BookSearchField.TITLE, 1));
    BookSearchState updated =
        new BookSearchState(
            userId, "자바", List.of(), Set.of("9781234567897"), Map.of(BookSearchField.TITLE, 2));
    store.save(searchId, original);

    // when
    store.save(searchId, updated);

    // then
    assertThat(store.findById(searchId))
        .hasValueSatisfying(
            actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(updated));
  }

  /** 저장된 검색 상태를 삭제하면 같은 ID로 다시 조회할 수 없다. */
  @Test
  void 삭제한_검색_상태는_조회되지_않는다() {
    // given
    UUID searchId = UUID.randomUUID();
    searchIds.add(searchId);
    BookSearchState state =
        new BookSearchState(
            UUID.randomUUID(), "자바", List.of(), Set.of(), Map.of(BookSearchField.TITLE, 1));
    store.save(searchId, state);
    assertThat(store.findById(searchId)).isPresent();

    // when
    store.delete(searchId);

    // then
    assertThat(store.findById(searchId)).isEmpty();
  }

  /** 운영의 30분 TTL 설정을 확인한 뒤 테스트 키만 1초로 단축하여 실제 만료 후 조회를 검증한다. */
  @Test
  void 저장한_검색_상태에_TTL이_적용되고_만료되면_조회되지_않는다() {
    // given
    UUID searchId = UUID.randomUUID();
    searchIds.add(searchId);
    BookSearchState state =
        new BookSearchState(
            UUID.randomUUID(), "자바", List.of(), Set.of(), Map.of(BookSearchField.TITLE, 1));
    store.save(searchId, state);
    String key = "myshelf:book-search:" + searchId;
    Long remainingMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
    assertThat(remainingMillis).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(30).toMillis());
    assertThat(store.findById(searchId)).isPresent();

    // when: 운영 TTL 상수는 변경하지 않고 이 테스트의 키에만 짧은 만료 시간을 지정한다.
    assertThat(redisTemplate.expire(key, Duration.ofSeconds(1))).isTrue();

    // then: 고정 시간 동안 sleep하지 않고, 최대 5초 안에 만료되는지 반복 확인한다.
    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(store.findById(searchId)).isEmpty());
  }

  @Test
  void 같은_검색_상태를_다시_저장하면_TTL을_갱신한다() {
    UUID searchId = UUID.randomUUID();
    searchIds.add(searchId);
    BookSearchState state =
        new BookSearchState(
            UUID.randomUUID(), "자바", List.of(), Set.of(), Map.of(BookSearchField.TITLE, 1));
    store.save(searchId, state);
    String key = "myshelf:book-search:" + searchId;
    assertThat(redisTemplate.expire(key, Duration.ofMinutes(1))).isTrue();
    assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS))
        .isPositive()
        .isLessThanOrEqualTo(Duration.ofMinutes(1).toMillis());

    store.save(searchId, state);

    assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS))
        .isGreaterThan(Duration.ofMinutes(1).toMillis())
        .isLessThanOrEqualTo(Duration.ofMinutes(30).toMillis());
  }

  // 테스트에서 명시한 구성만 사용하여 PostgreSQL·외부 API 빈 로딩을 방지한다.
  @Configuration(proxyBeanMethods = false)
  @Import({RedisConfig.class, RedisBookSearchStateStore.class})
  @ImportAutoConfiguration({DataRedisAutoConfiguration.class, JacksonAutoConfiguration.class})
  static class RedisTestConfig {}
}
