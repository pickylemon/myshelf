package com.myshelf.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

@Configuration
public class NationalLibraryClientConfig {

  @Bean("nationalLibraryRestClient")
  public RestClient nationalLibraryRestClient(
      RestClient.Builder builder,
      @Value("${national-library.connect-timeout-ms:2000}") int connectTimeoutMillis,
      @Value("${national-library.read-timeout-ms:5000}") int readTimeoutMillis) {
    Assert.isTrue(connectTimeoutMillis > 0, "도서관 API 연결 시간 제한은 양수여야 합니다.");
    Assert.isTrue(readTimeoutMillis > 0, "도서관 API 응답 시간 제한은 양수여야 합니다.");

    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMillis)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeoutMillis);

    return builder.baseUrl("https://www.nl.go.kr").requestFactory(requestFactory).build();
  }
}
