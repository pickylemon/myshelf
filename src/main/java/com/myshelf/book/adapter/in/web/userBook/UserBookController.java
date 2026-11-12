package com.myshelf.book.adapter.in.web.userBook;

import com.myshelf.auth.adapter.in.security.JwtUserIdResolver;
import com.myshelf.book.application.port.in.reading.UserBookUpdateReadingStatusCommand;
import com.myshelf.book.application.port.in.reading.UserBookUpdateReadingStatusUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UserBookController {
  private final UserBookUpdateReadingStatusUseCase userBookUpdateReadingStatusUseCase;

  @PatchMapping("/user-books/{userBookId}")
  public ResponseEntity<Void> updateReadingStatus(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID userBookId,
      @RequestBody @Valid UserBookReadingStatusUpdateRequest request) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    userBookUpdateReadingStatusUseCase.update(
        new UserBookUpdateReadingStatusCommand(userId, userBookId, request.readingStatus()));

    return ResponseEntity.noContent().build();
  }
}
