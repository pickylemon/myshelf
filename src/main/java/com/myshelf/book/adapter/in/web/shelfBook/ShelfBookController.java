package com.myshelf.book.adapter.in.web.shelfBook;

import com.myshelf.auth.adapter.in.security.JwtUserIdResolver;
import com.myshelf.book.application.port.in.shelf.ShelfBookDeleteCommand;
import com.myshelf.book.application.port.in.shelf.ShelfBookRemoveUseCase;
import com.myshelf.book.application.port.in.shelf.ShelfBookUpdateCommand;
import com.myshelf.book.application.port.in.shelf.ShelfBookUpdateUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ShelfBookController {
  private final ShelfBookUpdateUseCase shelfBookUpdateUseCase;
  private final ShelfBookRemoveUseCase shelfBookRemoveUseCase;

  @PatchMapping("/shelf-books/{shelfBookId}")
  public ResponseEntity<Void> updateMemo(
      @PathVariable @NotNull UUID shelfBookId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody @Valid ShelfBookMemoUpdateRequest updateRequest) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    ShelfBookUpdateCommand command =
        new ShelfBookUpdateCommand(userId, shelfBookId, updateRequest.memo());

    shelfBookUpdateUseCase.updateMemo(command);

    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/shelf-books")
  public ResponseEntity<Void> removeShelfBook(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ShelfBookDeleteRequest deleteRequest) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    shelfBookRemoveUseCase.delete(new ShelfBookDeleteCommand(userId, deleteRequest.shelfBookIds()));

    return ResponseEntity.noContent().build();
  }
}
