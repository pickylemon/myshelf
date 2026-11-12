package com.myshelf.book.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.myshelf.book.application.model.BookSearchState;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;

/** Redis 연결 없이 저장소의 예외 변환 경계를 검증한다. */
@ExtendWith(MockitoExtension.class)
class RedisBookSearchStateStoreTest {

  @Mock RedisTemplate<String, BookSearchState> redisTemplate;
  @Mock ValueOperations<String, BookSearchState> valueOperations;

  private RedisBookSearchStateStore store;

  @BeforeEach
  void setUp() {
    store = new RedisBookSearchStateStore(redisTemplate);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  @ParameterizedTest
  @MethodSource("connectionErrors")
  void 저장_조회_삭제의_연결_장애와_시간_초과를_저장소_장애로_변환한다(DataAccessException failure) {
    UUID searchId = UUID.randomUUID();
    String key = "myshelf:book-search:" + searchId;
    BookSearchState state =
        new BookSearchState(UUID.randomUUID(), "자바", List.of(), Set.of(), Map.of());
    doThrow(failure).when(valueOperations).set(key, state, Duration.ofMinutes(30));
    when(valueOperations.get(key)).thenThrow(failure);
    when(redisTemplate.delete(key)).thenThrow(failure);

    assertThatThrownBy(() -> store.save(searchId, state))
        .isInstanceOfSatisfying(
            BookException.class,
            ex ->
                assertThat(ex.getErrorCode())
                    .isEqualTo(BookErrorCode.SEARCH_SESSION_STORE_UNAVAILABLE))
        .hasCause(failure);
    assertThatThrownBy(() -> store.findById(searchId))
        .isInstanceOfSatisfying(
            BookException.class,
            ex ->
                assertThat(ex.getErrorCode())
                    .isEqualTo(BookErrorCode.SEARCH_SESSION_STORE_UNAVAILABLE))
        .hasCause(failure);
    assertThatThrownBy(() -> store.delete(searchId))
        .isInstanceOfSatisfying(
            BookException.class,
            ex ->
                assertThat(ex.getErrorCode())
                    .isEqualTo(BookErrorCode.SEARCH_SESSION_STORE_UNAVAILABLE))
        .hasCause(failure);
  }

  static Stream<DataAccessException> connectionErrors() {
    return Stream.of(
        new RedisConnectionFailureException("test connection failure"),
        new QueryTimeoutException("test timeout"));
  }

  @Test
  void 키가_없으면_장애_예외_없이_빈_결과를_반환한다() {
    UUID searchId = UUID.randomUUID();
    when(valueOperations.get("myshelf:book-search:" + searchId)).thenReturn(null);

    assertThat(store.findById(searchId)).isEmpty();
  }

  @Test
  void 역직렬화_오류는_연결_장애로_변환하지_않는다() {
    UUID searchId = UUID.randomUUID();
    SerializationException failure = new SerializationException("test invalid data");
    when(valueOperations.get("myshelf:book-search:" + searchId)).thenThrow(failure);

    assertThatThrownBy(() -> store.findById(searchId)).isSameAs(failure);
  }
}
