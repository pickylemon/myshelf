package com.myshelf.shelf.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myshelf.shelf.application.port.in.ShelfCreateCommand;
import com.myshelf.shelf.application.port.out.ShelfRepositoryPort;
import com.myshelf.shelf.domain.Shelf;
import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
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

/** 저장 포트를 Mock하여 사용자별 Shelf 이름 중복 검사와 생성 흐름을 검증한다. */
@ExtendWith(MockitoExtension.class)
class ShelfServiceTest {

  @Mock ShelfRepositoryPort shelfRepositoryPort;

  private ShelfService shelfService;

  @BeforeEach
  void setUp() {
    shelfService = new ShelfService(shelfRepositoryPort);
  }

  /** 같은 사용자의 Shelf 이름이 중복되면 DUPLICATE_NAME 예외를 던지고 저장하지 않는다. */
  @Test
  void duplicateNameForUserFailsWithoutSaving() {
    UUID userId = UUID.randomUUID();
    when(shelfRepositoryPort.existsByUserIdAndName(userId, "읽고 싶은 책")).thenReturn(true);

    assertThatThrownBy(() -> shelfService.create(new ShelfCreateCommand(userId, "읽고 싶은 책", null)))
        .isInstanceOfSatisfying(
            ShelfException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ShelfErrorCode.DUPLICATE_NAME));

    verify(shelfRepositoryPort).existsByUserIdAndName(userId, "읽고 싶은 책");
    verify(shelfRepositoryPort, never()).save(any());
  }

  /** 중복 검사 후 Shelf의 소유자·이름·설명을 전달하고 반환된 ID를 사용한다. */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = "주말에 읽을 책")
  void createSavesShelfAndReturnsSavedId(String description) {
    UUID userId = UUID.randomUUID();
    UUID savedId = UUID.randomUUID();
    String name = "읽고 싶은 책";
    when(shelfRepositoryPort.existsByUserIdAndName(userId, name)).thenReturn(false);
    when(shelfRepositoryPort.save(any(Shelf.class)))
        .thenAnswer(
            invocation -> {
              Shelf shelf = invocation.getArgument(0);
              return new Shelf(savedId, shelf.getName(), shelf.getDescription(), shelf.getUserId());
            });

    UUID result = shelfService.create(new ShelfCreateCommand(userId, name, description));

    ArgumentCaptor<Shelf> captor = ArgumentCaptor.forClass(Shelf.class);
    InOrder order = inOrder(shelfRepositoryPort);
    order.verify(shelfRepositoryPort).existsByUserIdAndName(userId, name);
    order.verify(shelfRepositoryPort).save(captor.capture());
    Shelf shelf = captor.getValue();
    assertThat(shelf.getId()).isNotNull();
    assertThat(shelf.getUserId()).isEqualTo(userId);
    assertThat(shelf.getName()).isEqualTo(name);
    assertThat(shelf.getDescription()).isEqualTo(description);
    assertThat(result).isEqualTo(savedId);
  }
}
