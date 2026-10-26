package com.myshelf.shelf.adapter.in.web;

import com.myshelf.shelf.application.port.in.ShelfCreateCommand;
import com.myshelf.shelf.application.port.in.ShelfCreateUseCase;
import com.myshelf.shelf.application.port.in.ShelfGetUseCase;
import com.myshelf.shelf.application.port.in.ShelfListUseCase;
import com.myshelf.shelf.domain.Shelf;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shelves")
@RequiredArgsConstructor
public class ShelfController {
  private final ShelfCreateUseCase shelfCreateUseCase;
  private final ShelfListUseCase shelfListUseCase;
  private final ShelfGetUseCase shelfGetUseCase;

  @PostMapping
  public ResponseEntity<UUID> createShelf(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ShelfCreateRequest shelfCreateRequest) {
    UUID userId = resolveUserId(jwt);
    ShelfCreateCommand shelfCreateCommand = shelfCreateRequest.toCommand(userId);
    UUID shelfId = shelfCreateUseCase.create(shelfCreateCommand);

    return ResponseEntity.status(HttpStatus.CREATED).body(shelfId);
  }

  @GetMapping
  public ResponseEntity<List<ShelfResponse>> getShelves(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = resolveUserId(jwt);
    List<Shelf> shelves = shelfListUseCase.list(userId);
    List<ShelfResponse> responses = shelves.stream().map(ShelfResponse::from).toList();

    return ResponseEntity.ok(responses);
  }

  @GetMapping("/{shelfId}")
  public ResponseEntity<ShelfResponse> getShelfDetail(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID shelfId) {
    UUID userId = resolveUserId(jwt);
    Shelf shelf = shelfGetUseCase.get(userId, shelfId);

    return ResponseEntity.ok(ShelfResponse.from(shelf));
  }

  private UUID resolveUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
