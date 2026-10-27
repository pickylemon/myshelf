package com.myshelf.shelf.adapter.in.web;

import com.myshelf.shelf.application.port.in.ShelfCreateCommand;
import com.myshelf.shelf.application.port.in.ShelfCreateUseCase;
import com.myshelf.shelf.application.port.in.ShelfDeleteUseCase;
import com.myshelf.shelf.application.port.in.ShelfGetUseCase;
import com.myshelf.shelf.application.port.in.ShelfListUseCase;
import com.myshelf.shelf.application.port.in.ShelfUpdateCommand;
import com.myshelf.shelf.application.port.in.ShelfUpdateUseCase;
import com.myshelf.shelf.domain.Shelf;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shelves")
@RequiredArgsConstructor
public class ShelfController {
  private final ShelfCreateUseCase shelfCreateUseCase;
  private final ShelfListUseCase shelfListUseCase;
  private final ShelfGetUseCase shelfGetUseCase;
  private final ShelfUpdateUseCase shelfUpdateUseCase;
  private final ShelfDeleteUseCase shelfDeleteUseCase;

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

  @PutMapping("/{shelfId}")
  public ResponseEntity<ShelfResponse> updateShelf(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shelfId,
      @RequestBody @Valid ShelfUpdateRequest shelfUpdateRequest) {
    UUID userId = resolveUserId(jwt);
    ShelfUpdateCommand command = shelfUpdateRequest.toCommand(userId, shelfId);
    Shelf shelf = shelfUpdateUseCase.update(command);

    return ResponseEntity.ok(ShelfResponse.from(shelf));
  }

  @DeleteMapping("/{shelfId}")
  public ResponseEntity<Void> deleteShelf(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID shelfId) {
    UUID userId = resolveUserId(jwt);
    shelfDeleteUseCase.delete(shelfId, userId);

    return ResponseEntity.noContent().build();
  }
}
