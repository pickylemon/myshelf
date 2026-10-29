package com.myshelf.book.adapter.in.web;

import com.myshelf.book.application.dto.ExternalBookSearchResult;
import com.myshelf.book.application.port.in.ExternalBookSearchUseCase;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class BookController {
  private final ExternalBookSearchUseCase externalBookSearchUseCase;

  @GetMapping("/books/search")
  public ResponseEntity<ExternalBookSearchResponse> searchExternalBooks(
      @RequestParam String keyword, @RequestParam(defaultValue = "1") int page) {

    ExternalBookSearchResult searchResult = externalBookSearchUseCase.search(keyword, page);
    return ResponseEntity.ok(ExternalBookSearchResponse.from(searchResult));
  }
}
