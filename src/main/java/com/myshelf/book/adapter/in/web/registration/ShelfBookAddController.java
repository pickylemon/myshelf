package com.myshelf.book.adapter.in.web.registration;

import com.myshelf.auth.adapter.in.security.JwtUserIdResolver;
import com.myshelf.book.application.dto.AddedBook;
import com.myshelf.book.application.port.in.registration.ShelfBookAddUseCase;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ShelfBookAddController {
  private final ShelfBookAddUseCase shelfBookAddUseCase;

  // 서재에 도서 등록
  @PostMapping("/shelves/{shelfId}/books")
  public ResponseEntity<ShelfBookAddResponse> add(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shelfId,
      @RequestBody @Valid ShelfBookAddRequest shelfBookAddRequest) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    List<AddedBook> addedBooks =
        shelfBookAddUseCase.add(shelfBookAddRequest.toCommand(userId, shelfId));
    ShelfBookAddResponse response = ShelfBookAddResponse.from(addedBooks);
    return ResponseEntity.ok(response);
  }
}
