package com.myshelf.shelf.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myshelf.shelf.application.port.in.ShelfCreateCommand;
import com.myshelf.shelf.application.port.in.ShelfUpdateCommand;
import com.myshelf.shelf.application.port.out.ShelfRepositoryPort;
import com.myshelf.shelf.domain.Shelf;
import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 저장 포트를 Mock하여 Shelf 생성·조회·수정·삭제 흐름과 실패 처리를 검증한다. */
@ExtendWith(MockitoExtension.class)
class ShelfServiceTest {

  @Mock ShelfRepositoryPort shelfRepositoryPort;

  private ShelfService shelfService;

  @BeforeEach
  void setUp() {
    shelfService = new ShelfService(shelfRepositoryPort);
  }

  // 생성

  /** 중복 검사 후 Shelf의 소유자·이름·설명을 전달하고 반환된 ID를 사용한다. */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = "주말에 읽을 책")
  void 서재를_생성하고_저장된_ID를_반환한다(String description) {
    UUID userId = UUID.randomUUID();
    UUID savedId = UUID.randomUUID();
    String name = "읽고 싶은 책";
    when(shelfRepositoryPort.existsByUserIdAndName(userId, name)).thenReturn(false);
    when(shelfRepositoryPort.save(any(Shelf.class)))
        .thenAnswer(
            invocation -> {
              Shelf shelf = invocation.getArgument(0);
              return new Shelf(savedId, shelf.name(), shelf.description(), shelf.userId());
            });

    UUID result = shelfService.create(new ShelfCreateCommand(userId, name, description));

    ArgumentCaptor<Shelf> captor = ArgumentCaptor.forClass(Shelf.class);
    InOrder order = inOrder(shelfRepositoryPort);
    order.verify(shelfRepositoryPort).existsByUserIdAndName(userId, name);
    order.verify(shelfRepositoryPort).save(captor.capture());
    Shelf shelf = captor.getValue();
    assertThat(shelf.id()).isNotNull();
    assertThat(shelf.userId()).isEqualTo(userId);
    assertThat(shelf.name()).isEqualTo(name);
    assertThat(shelf.description()).isEqualTo(description);
    assertThat(result).isEqualTo(savedId);
  }

  /** 같은 사용자의 Shelf 이름이 중복되면 DUPLICATE_NAME 예외를 던지고 저장하지 않는다. */
  @Test
  void 생성할_이름이_중복이면_저장하지_않는다() {
    UUID userId = UUID.randomUUID();
    when(shelfRepositoryPort.existsByUserIdAndName(userId, "읽고 싶은 책")).thenReturn(true);

    assertThatThrownBy(() -> shelfService.create(new ShelfCreateCommand(userId, "읽고 싶은 책", null)))
        .isInstanceOfSatisfying(
            ShelfException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ShelfErrorCode.DUPLICATE_NAME));

    verify(shelfRepositoryPort).existsByUserIdAndName(userId, "읽고 싶은 책");
    verify(shelfRepositoryPort, never()).save(any());
  }

  // 목록 조회

  /** 목록 조회에 인증 사용자 ID를 전달하고 조회된 Shelf를 반환한다. 데이터 격리는 DB 테스트에서 검증한다. */
  @Test
  void 인증_사용자의_서재_목록을_반환한다() {
    UUID userId = UUID.randomUUID();
    Shelf first = new Shelf(UUID.randomUUID(), "소설", "읽을 서재", userId);
    Shelf second = new Shelf(UUID.randomUUID(), "인문", null, userId);
    when(shelfRepositoryPort.list(userId)).thenReturn(List.of(first, second));

    assertThat(shelfService.list(userId)).containsExactlyInAnyOrder(first, second);

    verify(shelfRepositoryPort).list(userId);
  }

  /** 자신의 Shelf가 없으면 예외를 발생시키지 않고 빈 목록을 반환한다. */
  @Test
  void 서재가_없으면_빈_목록을_반환한다() {
    UUID userId = UUID.randomUUID();
    when(shelfRepositoryPort.list(userId)).thenReturn(List.of());

    assertThat(shelfService.list(userId)).isEmpty();
  }

  // 단건 조회

  /** 단건 조회에는 사용자 ID와 Shelf ID를 함께 전달하고 찾은 도메인 객체를 반환한다. */
  @Test
  void 소유자_조건으로_조회한_서재를_반환한다() {
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    Shelf shelf = new Shelf(shelfId, "내 서재", "설명", userId);
    when(shelfRepositoryPort.findByIdAndUserId(shelfId, userId)).thenReturn(Optional.of(shelf));

    assertThat(shelfService.get(shelfId, userId)).isSameAs(shelf);

    verify(shelfRepositoryPort).findByIdAndUserId(shelfId, userId);
  }

  /** 포트의 조회 결과가 없으면 사유를 구분하지 않고 SHELF_NOT_FOUND로 변환한다. */
  @Test
  void 단건_조회_결과가_없으면_SHELF_NOT_FOUND() {
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    when(shelfRepositoryPort.findByIdAndUserId(shelfId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> shelfService.get(shelfId, userId))
        .isInstanceOfSatisfying(
            ShelfException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ShelfErrorCode.SHELF_NOT_FOUND));
  }

  // 수정

  /** 소유권·이름 중복 확인 후 변경할 필드를 전달하고 포트가 반환한 수정 결과를 반환한다. */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = "description")
  void 이름과_설명을_정상적으로_수정한다(String description) {
    // given
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    String newName = "서재2";
    Instant createdAt = Instant.parse("2026-10-01T00:00:00Z");
    Instant updatedAt = Instant.parse("2026-10-02T00:00:00Z");

    Shelf foundShelf = new Shelf(shelfId, "서재1", "기존 설명", userId, createdAt, createdAt);
    Shelf updatedShelf = new Shelf(shelfId, newName, description, userId, createdAt, updatedAt);
    when(shelfRepositoryPort.findByIdAndUserId(shelfId, userId))
        .thenReturn(Optional.of(foundShelf));
    when(shelfRepositoryPort.existsByUserIdAndNameExcludingSelf(any(Shelf.class)))
        .thenReturn(false);
    when(shelfRepositoryPort.update(any(Shelf.class))).thenReturn(Optional.of(updatedShelf));

    // when
    Shelf result =
        shelfService.update(new ShelfUpdateCommand(userId, shelfId, newName, description));

    // then
    assertThat(result).isSameAs(updatedShelf);
    ArgumentCaptor<Shelf> captor = ArgumentCaptor.forClass(Shelf.class);
    InOrder order = inOrder(shelfRepositoryPort);
    order.verify(shelfRepositoryPort).findByIdAndUserId(shelfId, userId);
    order.verify(shelfRepositoryPort).existsByUserIdAndNameExcludingSelf(captor.capture());
    order.verify(shelfRepositoryPort).update(same(captor.getValue()));

    Shelf actual = captor.getValue();
    assertThat(actual.id()).isEqualTo(shelfId);
    assertThat(actual.userId()).isEqualTo(userId);
    assertThat(actual.name()).isEqualTo(newName);
    assertThat(actual.description()).isEqualTo(description);
  }

  /** 자기 자신을 제외한 중복이 없으면 이름을 유지하고 설명만 변경할 수 있다. */
  @Test
  void 기존_이름을_유지하고_설명만_수정할_수_있다() {
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    String name = "서재1";
    Shelf foundShelf = new Shelf(shelfId, name, "기존 설명", userId);
    Shelf updatedShelf = new Shelf(shelfId, name, "새 설명", userId);
    when(shelfRepositoryPort.findByIdAndUserId(shelfId, userId))
        .thenReturn(Optional.of(foundShelf));
    when(shelfRepositoryPort.existsByUserIdAndNameExcludingSelf(any(Shelf.class)))
        .thenReturn(false);
    when(shelfRepositoryPort.update(any(Shelf.class))).thenReturn(Optional.of(updatedShelf));

    Shelf result = shelfService.update(new ShelfUpdateCommand(userId, shelfId, name, "새 설명"));

    assertThat(result).isSameAs(updatedShelf);
    ArgumentCaptor<Shelf> captor = ArgumentCaptor.forClass(Shelf.class);
    verify(shelfRepositoryPort).update(captor.capture());
    Shelf actual = captor.getValue();
    assertThat(actual.id()).isEqualTo(shelfId);
    assertThat(actual.userId()).isEqualTo(userId);
    assertThat(actual.name()).isEqualTo(name);
    assertThat(actual.description()).isEqualTo("새 설명");
  }

  /** 소유자 조건으로 찾지 못하면 중복 검사와 업데이트를 수행하지 않는다. HTTP 상태는 웹 테스트의 책임이다. */
  @Test
  void 소유자_조건_조회가_비어있으면_SHELF_NOT_FOUND() {
    // given
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    String description = "대충설명";
    String newName = "서재1";

    when(shelfRepositoryPort.findByIdAndUserId(shelfId, userId)).thenReturn(Optional.empty());

    // when, then
    assertThatThrownBy(
            () ->
                shelfService.update(new ShelfUpdateCommand(userId, shelfId, newName, description)))
        .isInstanceOfSatisfying(
            ShelfException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ShelfErrorCode.SHELF_NOT_FOUND));

    verify(shelfRepositoryPort).findByIdAndUserId(shelfId, userId);
    verify(shelfRepositoryPort, never()).existsByUserIdAndNameExcludingSelf(any());
    verify(shelfRepositoryPort, never()).update(any());
  }

  /** 중복 검사에는 기존 이름이 아닌 새 이름과 자기 자신을 제외할 식별자를 전달한다. */
  @Test
  void 변경하려는_이름이_중복이면_DUPLICATE_NAME() {
    // given
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    String description = "대충설명";
    String oldName = "서재1";
    String newName = "서재2";
    Shelf foundShelf = new Shelf(shelfId, oldName, description, userId);

    when(shelfRepositoryPort.findByIdAndUserId(shelfId, userId))
        .thenReturn(Optional.of(foundShelf));
    when(shelfRepositoryPort.existsByUserIdAndNameExcludingSelf(any(Shelf.class))).thenReturn(true);

    // when, then
    assertThatThrownBy(
            () ->
                shelfService.update(new ShelfUpdateCommand(userId, shelfId, newName, description)))
        .isInstanceOfSatisfying(
            ShelfException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ShelfErrorCode.DUPLICATE_NAME));

    ArgumentCaptor<Shelf> captor = ArgumentCaptor.forClass(Shelf.class);
    verify(shelfRepositoryPort).existsByUserIdAndNameExcludingSelf(captor.capture());
    Shelf actual = captor.getValue();
    assertThat(actual.id()).isEqualTo(shelfId);
    assertThat(actual.userId()).isEqualTo(userId);
    assertThat(actual.name()).isEqualTo(newName);
    verify(shelfRepositoryPort, never()).update(any());
  }

  /** 사전 조회는 성공해도 최종 업데이트 결과가 없으면 부재 예외로 처리한다. 실제 동시 삭제는 재현하지 않는다. */
  @Test
  void 사전_조회_후_업데이트_결과가_없으면_SHELF_NOT_FOUND() {
    // given
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    String description = "대충설명";
    String oldName = "서재1";
    String newName = "서재2";
    Shelf foundShelf = new Shelf(shelfId, oldName, description, userId);

    when(shelfRepositoryPort.findByIdAndUserId(shelfId, userId))
        .thenReturn(Optional.of(foundShelf));
    when(shelfRepositoryPort.existsByUserIdAndNameExcludingSelf(any(Shelf.class)))
        .thenReturn(false);
    when(shelfRepositoryPort.update(any(Shelf.class))).thenReturn(Optional.empty());

    // when, then
    assertThatThrownBy(
            () ->
                shelfService.update(new ShelfUpdateCommand(userId, shelfId, newName, description)))
        .isInstanceOfSatisfying(
            ShelfException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ShelfErrorCode.SHELF_NOT_FOUND));

    verify(shelfRepositoryPort).update(any(Shelf.class));
  }

  /** 사전 검사를 통과해도 저장 포트가 중복 예외를 반환하면 성공으로 처리하지 않고 전파한다. */
  @Test
  void 업데이트_중_발생한_중복_예외를_전파한다() {
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    Shelf foundShelf = new Shelf(shelfId, "서재1", null, userId);
    ShelfException duplicate = new ShelfException(ShelfErrorCode.DUPLICATE_NAME);
    when(shelfRepositoryPort.findByIdAndUserId(shelfId, userId))
        .thenReturn(Optional.of(foundShelf));
    when(shelfRepositoryPort.existsByUserIdAndNameExcludingSelf(any(Shelf.class)))
        .thenReturn(false);
    when(shelfRepositoryPort.update(any(Shelf.class))).thenThrow(duplicate);

    assertThatThrownBy(
            () -> shelfService.update(new ShelfUpdateCommand(userId, shelfId, "서재2", null)))
        .isSameAs(duplicate);

    verify(shelfRepositoryPort).update(any(Shelf.class));
  }

  // 삭제

  /** 삭제가 성공하면 정상 종료하고 Shelf ID와 사용자 ID를 포트에 전달한다. */
  @Test
  void 정상_삭제() {
    // given
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    when(shelfRepositoryPort.delete(shelfId, userId)).thenReturn(true);

    // when
    shelfService.delete(shelfId, userId);

    // then
    verify(shelfRepositoryPort).delete(shelfId, userId);
  }

  /** 삭제 결과가 false이면 소유자 조건에 맞는 대상이 없다는 예외를 던진다. */
  @Test
  void 삭제_대상이_없으면_SHELF_NOT_FOUND() {
    // given
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    when(shelfRepositoryPort.delete(shelfId, userId)).thenReturn(false);

    // when, then
    assertThatThrownBy(() -> shelfService.delete(shelfId, userId))
        .isInstanceOfSatisfying(
            ShelfException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ShelfErrorCode.SHELF_NOT_FOUND));
  }
}
