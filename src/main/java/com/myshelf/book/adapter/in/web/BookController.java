package com.myshelf.book.adapter.in.web;

import com.myshelf.auth.adapter.in.security.JwtUserIdResolver;
import com.myshelf.book.application.dto.ExternalBookSearchResult;
import com.myshelf.book.application.port.in.ExternalBookSearchUseCase;
import com.myshelf.book.application.port.in.ShelfBookAddUseCase;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class BookController {
  private final ExternalBookSearchUseCase externalBookSearchUseCase;
  private final ShelfBookAddUseCase shelfBookAddUseCase;

  // 최초 도서 검색
  @GetMapping("/books/search")
  public ResponseEntity<ExternalBookSearchResponse> search(
      @AuthenticationPrincipal Jwt jwt, @RequestParam @NotBlank String keyword) {

    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    ExternalBookSearchResult searchResult =
        externalBookSearchUseCase.search(userId, keyword.strip());
    return ResponseEntity.ok(ExternalBookSearchResponse.from(searchResult));
  }

  // '더보기' 도서 검색
  @GetMapping("/books/search/{searchId}")
  public ResponseEntity<ExternalBookSearchResponse> loadMore(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID searchId) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    ExternalBookSearchResult searchResult = externalBookSearchUseCase.loadMore(userId, searchId);
    return ResponseEntity.ok(ExternalBookSearchResponse.from(searchResult));
  }

  @PostMapping("/books/{shelfId}")
  public ResponseEntity<ShelfBookAddResponse> add(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID shelfId) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    return null;
  }
}
