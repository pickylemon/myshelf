package com.myshelf.shelf.adapter.in.web;

import com.myshelf.shelf.application.port.in.ShelfCreateCommand;
import com.myshelf.shelf.application.port.in.ShelfCreateUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shelves")
@RequiredArgsConstructor
public class ShelfController {
  private final ShelfCreateUseCase shelfCreateUseCase;

  @PostMapping
  public ResponseEntity<UUID> createShelf(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ShelfCreateRequest shelfCreateRequest) {
    UUID userId = UUID.fromString(jwt.getSubject());
    ShelfCreateCommand shelfCreateCommand = shelfCreateRequest.toCommand(userId);
    UUID shelfId = shelfCreateUseCase.create(shelfCreateCommand);

    return ResponseEntity.status(HttpStatus.CREATED).body(shelfId);
  }
}
