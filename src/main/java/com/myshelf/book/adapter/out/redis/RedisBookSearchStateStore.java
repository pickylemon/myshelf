package com.myshelf.book.adapter.out.redis;

import com.myshelf.book.application.model.BookSearchState;
import com.myshelf.book.application.port.out.search.BookSearchStatePort;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("redis")
@RequiredArgsConstructor
public class RedisBookSearchStateStore implements BookSearchStatePort {
  private static final Duration TTL = Duration.ofMinutes(30);
  private static final String KEY_PREFIX = "myshelf:book-search:";

  private final RedisTemplate<String, BookSearchState> redisTemplate;

  @Override
  public void save(UUID searchId, BookSearchState state) {
    try {
      redisTemplate.opsForValue().set(KEY_PREFIX + searchId, state, TTL);
    } catch (RedisConnectionFailureException | QueryTimeoutException e) {
      throw new BookException(BookErrorCode.SEARCH_SESSION_STORE_UNAVAILABLE, e);
    }
  }

  @Override
  public Optional<BookSearchState> findById(UUID searchId) {
    try {
      return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + searchId));
    } catch (RedisConnectionFailureException | QueryTimeoutException e) {
      throw new BookException(BookErrorCode.SEARCH_SESSION_STORE_UNAVAILABLE, e);
    }
  }

  @Override
  public void delete(UUID searchId) {
    try {
      redisTemplate.delete(KEY_PREFIX + searchId);
    } catch (RedisConnectionFailureException | QueryTimeoutException e) {
      throw new BookException(BookErrorCode.SEARCH_SESSION_STORE_UNAVAILABLE, e);
    }
  }
}
