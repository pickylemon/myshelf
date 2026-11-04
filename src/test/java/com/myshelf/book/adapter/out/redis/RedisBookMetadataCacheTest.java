package com.myshelf.book.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;

@ExtendWith(MockitoExtension.class)
class RedisBookMetadataCacheTest {
  @Mock RedisTemplate<String, BookSearchMeta> template;
  @Mock ValueOperations<String, BookSearchMeta> values;
  private RedisBookMetadataCache cache;

  @BeforeEach
  void setUp() {
    cache = new RedisBookMetadataCache(template);
    when(template.opsForValue()).thenReturn(values);
  }

  // TODO(테스트 구현예졍): QueryTimeoutException을 조회·저장에서 발생시켜
  // BOOK_METADATA_CACHE_UNAVAILABLE로 변환되고 원인 예외가 보존되는지 검증한다.

  @Test
  void 캐시의_도서정보를_반환한다() {
    BookSearchMeta metadata = book();
    when(values.get("myshelf:book-metadata" + metadata.isbn())).thenReturn(metadata);

    assertThat(cache.findByIsbn(metadata.isbn())).contains(metadata);
  }

  @Test
  void 키가_없으면_빈_결과를_반환한다() {
    when(values.get("myshelf:book-metadatamissing")).thenReturn(null);

    assertThat(cache.findByIsbn("missing")).isEmpty();
  }

  @Test
  void ISBN_키에_도서정보와_30분_TTL을_전달한다() {
    BookSearchMeta metadata = book();

    cache.save(metadata);

    verify(values).set("myshelf:book-metadata" + metadata.isbn(), metadata, Duration.ofMinutes(30));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 조회와_저장의_연결_실패는_캐시_장애로_변환한다(boolean saving) {
    BookSearchMeta metadata = book();
    String key = "myshelf:book-metadata" + metadata.isbn();
    RedisConnectionFailureException failure =
        new RedisConnectionFailureException("test connection failure");
    if (saving) {
      doThrow(failure).when(values).set(key, metadata, Duration.ofMinutes(30));
    } else {
      when(values.get(key)).thenThrow(failure);
    }

    assertThatThrownBy(
            () -> {
              if (saving) cache.save(metadata);
              else cache.findByIsbn(metadata.isbn());
            })
        .isInstanceOfSatisfying(
            BookException.class,
            ex ->
                assertThat(ex.getErrorCode())
                    .isEqualTo(BookErrorCode.BOOK_METADATA_CACHE_UNAVAILABLE))
        .hasCause(failure);
  }

  @Test
  void 역직렬화_오류를_캐시_부재나_연결_장애로_바꾸지_않는다() {
    SerializationException failure = new SerializationException("test invalid JSON");
    when(values.get("myshelf:book-metadataisbn")).thenThrow(failure);

    assertThatThrownBy(() -> cache.findByIsbn("isbn")).isSameAs(failure);
  }

  private BookSearchMeta book() {
    return new BookSearchMeta("isbn", "제목", "저자", "출판사", LocalDate.of(2026, 10, 1), "cover");
  }
}
