package com.myshelf.book.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.global.config.RedisConfig;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 테스트 Redis DB 1에서 운영 Serializer와 Cache 구현을 함께 검증한다. */
@SpringBootTest(classes = RedisBookMetadataCacheIntegrationTest.RedisTestConfig.class)
@ActiveProfiles({"test", "redis"})
class RedisBookMetadataCacheIntegrationTest {
  @Autowired RedisBookMetadataCache cache;
  @Autowired DataRedisProperties properties;

  @Autowired
  @Qualifier("bookMetadataRedisTemplate")
  RedisTemplate<String, BookSearchMeta> template;

  private final List<String> keys = new ArrayList<>();

  @BeforeEach
  void 테스트용_Redis_DB를_사용하는지_확인한다() {
    assertThat(properties.getDatabase()).isEqualTo(1);
  }

  @AfterEach
  void cleanUp() {
    for (String key : keys) template.delete(key);
  }

  /** 같은 ISBN의 재저장은 해당 키의 짧아진 TTL을 다시 30분으로 설정한다. */
  @Test
  void 같은_ISBN의_재저장은_TTL을_갱신한다() {
    String isbn = newIsbn();
    BookSearchMeta book = new BookSearchMeta(isbn, "제목", null, null, null, null);
    cache.save(book);
    String key = "myshelf:book-metadata" + isbn;
    assertThat(template.expire(key, Duration.ofSeconds(5))).isTrue();
    Long shortened = template.getExpire(key, TimeUnit.MILLISECONDS);

    cache.save(book);

    assertThat(template.getExpire(key, TimeUnit.MILLISECONDS))
        .isGreaterThan(shortened)
        .isLessThanOrEqualTo(Duration.ofMinutes(30).toMillis());
    assertThat(cache.findByIsbn(isbn)).contains(book);
  }

  @Test
  void 날짜를_포함한_도서정보의_모든_필드를_저장하고_복원한다() {
    String isbn = newIsbn();
    BookSearchMeta expected =
        new BookSearchMeta(
            isbn, "제목", "저자", "출판사", LocalDate.of(2026, 10, 1), "https://example.com/cover");

    cache.save(expected);

    assertThat(cache.findByIsbn(isbn)).contains(expected);
  }

  @Test
  void 같은_ISBN으로_저장하면_새_정보로_덮어쓴다() {
    String isbn = newIsbn();
    cache.save(new BookSearchMeta(isbn, "이전 제목", "저자", null, null, null));
    BookSearchMeta updated = new BookSearchMeta(isbn, "새 제목", null, "새 출판사", null, "cover");

    cache.save(updated);

    assertThat(cache.findByIsbn(isbn)).contains(updated);
  }

  @Test
  void 존재하지_않는_ISBN은_빈_결과다() {
    assertThat(cache.findByIsbn(newIsbn())).isEmpty();
  }

  @Test
  void 저장시_30분_TTL이_적용되고_실제_만료_후에는_조회되지_않는다() {
    String isbn = newIsbn();
    cache.save(new BookSearchMeta(isbn, "제목", null, null, null, null));
    String key = "myshelf:book-metadata" + isbn;
    assertThat(template.getExpire(key, TimeUnit.MILLISECONDS))
        .isPositive()
        .isLessThanOrEqualTo(Duration.ofMinutes(30).toMillis());
    assertThat(cache.findByIsbn(isbn)).isPresent();

    assertThat(template.expire(key, Duration.ofSeconds(1))).isTrue();

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(cache.findByIsbn(isbn)).isEmpty());
  }

  private String newIsbn() {
    String isbn = "metadata-test-" + UUID.randomUUID();
    keys.add("myshelf:book-metadata" + isbn);
    return isbn;
  }

  @Configuration(proxyBeanMethods = false)
  @Import({RedisConfig.class, RedisBookMetadataCache.class})
  @ImportAutoConfiguration({DataRedisAutoConfiguration.class, JacksonAutoConfiguration.class})
  static class RedisTestConfig {}
}
