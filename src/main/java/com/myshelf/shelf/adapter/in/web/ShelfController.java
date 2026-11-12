package com.myshelf.shelf.adapter.in.web;

import com.myshelf.auth.adapter.in.security.JwtUserIdResolver;
import com.myshelf.book.application.dto.ShelfBookItem;
import com.myshelf.book.application.port.in.shelf.ShelfBookListUseCase;
import com.myshelf.shelf.application.port.in.ShelfCreateCommand;
import com.myshelf.shelf.application.port.in.ShelfCreateUseCase;
import com.myshelf.shelf.application.port.in.ShelfDeleteUseCase;
import com.myshelf.shelf.application.port.in.ShelfGetUseCase;
import com.myshelf.shelf.application.port.in.ShelfListUseCase;
import com.myshelf.shelf.application.port.in.ShelfUpdateCommand;
import com.myshelf.shelf.application.port.in.ShelfUpdateUseCase;
import com.myshelf.shelf.domain.Shelf;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shelves")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(
    responseCode = "401",
    description = "인증 실패(AUTHENTICATION_FAILED)",
    headers =
        @Header(
            name = "WWW-Authenticate",
            description = "Bearer 인증 안내",
            schema = @Schema(type = "string", example = "Bearer")),
    content =
        @Content(
            mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemDetail.class)))
public class ShelfController {
  private final ShelfCreateUseCase shelfCreateUseCase;
  private final ShelfListUseCase shelfListUseCase;
  private final ShelfGetUseCase shelfGetUseCase;
  private final ShelfUpdateUseCase shelfUpdateUseCase;
  private final ShelfDeleteUseCase shelfDeleteUseCase;
  private final ShelfBookListUseCase shelfBookListUseCase;

  @PostMapping
  @Operation(summary = "서재 생성", description = "소유자 사용자가 DB에 없으면 401(INVALID_SHELF_OWNER)을 반환한다.")
  @ApiResponse(
      responseCode = "201",
      description = "서재 생성 성공. Shelf ID 반환",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(type = "string", format = "uuid")))
  @ApiResponse(responseCode = "400", description = "잘못된 요청값 또는 JSON 형식", content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "사용자 내 서재 이름 중복(DUPLICATE_NAME)",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ResponseEntity<UUID> createShelf(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ShelfCreateRequest shelfCreateRequest) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    ShelfCreateCommand shelfCreateCommand = shelfCreateRequest.toCommand(userId);
    UUID shelfId = shelfCreateUseCase.create(shelfCreateCommand);

    return ResponseEntity.status(HttpStatus.CREATED).body(shelfId);
  }

  @GetMapping
  @Operation(summary = "서재 목록 조회")
  @ApiResponse(responseCode = "200", description = "서재 목록 반환. 없으면 빈 배열")
  public ResponseEntity<List<ShelfResponse>> getShelves(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    List<Shelf> shelves = shelfListUseCase.list(userId);
    List<ShelfResponse> responses = shelves.stream().map(ShelfResponse::from).toList();

    return ResponseEntity.ok(responses);
  }

  @GetMapping("/{shelfId}")
  @Operation(summary = "서재 상세 조회")
  @ApiResponse(responseCode = "200", description = "서재 상세 정보 반환")
  @ApiResponse(responseCode = "400", description = "잘못된 shelfId 형식", content = @Content)
  @ApiResponse(
      responseCode = "404",
      description = "미존재 또는 타인 소유 서재(SHELF_NOT_FOUND)",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ResponseEntity<ShelfResponse> getShelfDetail(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID shelfId) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    Shelf shelf = shelfGetUseCase.get(shelfId, userId);

    return ResponseEntity.ok(ShelfResponse.from(shelf));
  }

  @GetMapping("/{shelfId}/books")
  @Operation(summary = "서재 내 등록된 도서 조회")
  @ApiResponse(responseCode = "200", description = "서재 내 등록된 도서 정보 반환")
  public ResponseEntity<List<ShelfBookItem>> getShelfBooks(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID shelfId) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    List<ShelfBookItem> bookList = shelfBookListUseCase.list(shelfId, userId);

    return ResponseEntity.ok(bookList);
  }

  @PutMapping("/{shelfId}")
  @Operation(summary = "서재 수정", description = "설명이 null이면 기존 설명을 제거한다.")
  @ApiResponse(responseCode = "200", description = "수정된 서재 정보 반환")
  @ApiResponse(
      responseCode = "400",
      description = "잘못된 shelfId, 요청값 또는 JSON 형식",
      content = @Content)
  @ApiResponse(
      responseCode = "404",
      description = "미존재 또는 타인 소유 서재(SHELF_NOT_FOUND)",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "사용자 내 다른 서재와 이름 중복(DUPLICATE_NAME)",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ResponseEntity<ShelfResponse> updateShelf(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shelfId,
      @RequestBody @Valid ShelfUpdateRequest shelfUpdateRequest) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    ShelfUpdateCommand command = shelfUpdateRequest.toCommand(shelfId, userId);
    Shelf shelf = shelfUpdateUseCase.update(command);

    return ResponseEntity.ok(ShelfResponse.from(shelf));
  }

  @DeleteMapping("/{shelfId}")
  @Operation(summary = "서재 삭제")
  @ApiResponse(responseCode = "204", description = "서재 삭제 성공. 본문 없음", content = @Content)
  @ApiResponse(responseCode = "400", description = "잘못된 shelfId 형식", content = @Content)
  @ApiResponse(
      responseCode = "404",
      description = "미존재 또는 타인 소유 서재(SHELF_NOT_FOUND)",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ResponseEntity<Void> deleteShelf(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID shelfId) {
    UUID userId = JwtUserIdResolver.resolveUserId(jwt);
    shelfDeleteUseCase.delete(shelfId, userId);

    return ResponseEntity.noContent().build();
  }
}
