package com.myshelf.book.adapter.out.redis;

import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.port.out.BookMetadataCachePort;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import java.time.Duration;
import java.util.Optional;
import org.hibernate.QueryTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("redis")
public class RedisBookMetadataCache implements BookMetadataCachePort {
  private static final String KEY_PREFIX = "myshelf:book-metadata";
  private static final Duration TTL = Duration.ofMinutes(30);

  private final RedisTemplate<String, BookSearchMeta> redisTemplate;

  public RedisBookMetadataCache(
      @Qualifier("bookMetadataRedisTemplate") RedisTemplate<String, BookSearchMeta> redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  @Override
  public Optional<BookSearchMeta> findByIsbn(String isbn) {
    try {
      return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + isbn));
    } catch (RedisConnectionFailureException | QueryTimeoutException e) {
      throw new BookException(BookErrorCode.BOOK_METADATA_CACHE_UNAVAILABLE, e);
    }
  }

  @Override
  public void save(BookSearchMeta book) {
    try {
      redisTemplate.opsForValue().set(KEY_PREFIX + book.isbn(), book, TTL);
    } catch (RedisConnectionFailureException | QueryTimeoutException e) {
      throw new BookException(BookErrorCode.BOOK_METADATA_CACHE_UNAVAILABLE, e);
    }
  }
}
