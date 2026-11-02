package com.myshelf.global.config;

import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.model.BookSearchState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class RedisConfig {

  @Bean
  public RedisTemplate<String, BookSearchState> bookSearchStateRedisTemplate(
      RedisConnectionFactory connectionFactory, JsonMapper jsonMapper) {
    RedisTemplate<String, BookSearchState> template = new RedisTemplate<>();

    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(
        new JacksonJsonRedisSerializer<>(jsonMapper, BookSearchState.class));

    return template;
  }

  @Bean
  public RedisTemplate<String, BookSearchMeta> bookMetadataRedisTemplate(
      RedisConnectionFactory connectionFactory, JsonMapper jsonMapper) {
    RedisTemplate<String, BookSearchMeta> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(
        new JacksonJsonRedisSerializer<>(jsonMapper, BookSearchState.class));

    return template;
  }
}
