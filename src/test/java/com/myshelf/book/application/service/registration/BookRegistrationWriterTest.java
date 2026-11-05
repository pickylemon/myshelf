package com.myshelf.book.application.service.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.myshelf.book.application.dto.AddedBook;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.port.in.registration.ShelfBookAddCommand;
import com.myshelf.book.application.port.out.registration.BookRepositoryPort;
import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.domain.ShelfBook;
import com.myshelf.book.domain.UserBook;
import com.myshelf.shelf.application.port.in.ShelfGetUseCase;
import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 등록 분기와 전달값을 검증한다. Mockito 테스트로 실제 트랜잭션 동작을 검증하지 않는다. */
@ExtendWith(MockitoExtension.class)
class BookRegistrationWriterTest {
  @Mock BookRepositoryPort repository;
  @Mock ShelfGetUseCase shelfGetUseCase;
  private BookRegistrationWriter writer;
  private final UUID userId = UUID.randomUUID();
  private final UUID shelfId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    writer = new BookRegistrationWriter(repository, shelfGetUseCase);
  }

  @Test
  void 신규_도서의_메타데이터와_요청_상태로_UserBook과_연결을_생성한다() {
    BookSearchMeta metadata = new BookSearchMeta("isbn", "제목", "저자", "출판사", null, "cover");
    when(repository.findUserBookByUserIdAndIsbn(userId, "isbn")).thenReturn(Optional.empty());
    when(repository.createUserBook(any())).thenAnswer(invocation -> invocation.getArgument(0));
    UUID returnedId = UUID.randomUUID();
    when(repository.createShelfBook(any()))
        .thenAnswer(
            invocation -> {
              ShelfBook input = invocation.getArgument(0);
              return new ShelfBook(returnedId, input.shelfId(), input.userBookId());
            });

    List<AddedBook> result = writer.register(command(), Map.of("isbn", metadata));

    ArgumentCaptor<UserBook> bookCaptor = ArgumentCaptor.forClass(UserBook.class);
    verify(repository).createUserBook(bookCaptor.capture());
    UserBook created = bookCaptor.getValue();
    assertThat(created.id()).isNotNull();
    assertThat(created.userId()).isEqualTo(userId);
    assertThat(created.isbn()).isEqualTo("isbn");
    assertThat(created.title()).isEqualTo("제목");
    assertThat(created.author()).isEqualTo("저자");
    assertThat(created.publisher()).isEqualTo("출판사");
    assertThat(created.coverImageUrl()).isEqualTo("cover");
    assertThat(created.readingStatus()).isEqualTo(ReadingStatus.READ);
    ArgumentCaptor<ShelfBook> linkCaptor = ArgumentCaptor.forClass(ShelfBook.class);
    verify(repository).createShelfBook(linkCaptor.capture());
    assertThat(linkCaptor.getValue().shelfId()).isEqualTo(shelfId);
    assertThat(linkCaptor.getValue().userBookId()).isEqualTo(created.id());
    assertThat(linkCaptor.getValue().memo()).isNull();
    assertThat(result).containsExactly(new AddedBook("isbn", returnedId));
    verify(shelfGetUseCase).get(shelfId, userId);
    verify(repository, never()).updateReadingStatus(any(), any(), any());
  }

  @Test
  void 기존_도서는_선택한_상태로_변경하고_메모가_없는_새_연결을_생성한다() {
    UserBook existing = existing();
    when(repository.findUserBookByUserIdAndIsbn(userId, "isbn")).thenReturn(Optional.of(existing));
    when(repository.existsByShelfIdAndUserBookId(shelfId, existing.id())).thenReturn(false);
    when(repository.createShelfBook(any())).thenAnswer(invocation -> invocation.getArgument(0));

    List<AddedBook> result = writer.register(command(), Map.of());

    verify(repository).updateReadingStatus(existing.id(), userId, ReadingStatus.READ);
    verify(repository, never()).createUserBook(any());
    ArgumentCaptor<ShelfBook> captor = ArgumentCaptor.forClass(ShelfBook.class);
    verify(repository).createShelfBook(captor.capture());
    assertThat(captor.getValue().userBookId()).isEqualTo(existing.id());
    assertThat(captor.getValue().memo()).isNull();
    assertThat(result).containsExactly(new AddedBook("isbn", captor.getValue().id()));
  }

  @Test
  void 대상_서재에_이미_있으면_상태와_메모를_변경하지_않고_결과에서도_제외한다() {
    UserBook existing = existing();
    when(repository.findUserBookByUserIdAndIsbn(userId, "isbn")).thenReturn(Optional.of(existing));
    when(repository.existsByShelfIdAndUserBookId(shelfId, existing.id())).thenReturn(true);

    assertThat(writer.register(command(), Map.of())).isEmpty();

    verify(repository, never()).updateReadingStatus(any(), any(), any());
    verify(repository, never()).createUserBook(any());
    verify(repository, never()).createShelfBook(any());
  }

  @Test
  void 신규_메타데이터가_준비되지_않으면_명시적으로_실패한다() {
    when(repository.findUserBookByUserIdAndIsbn(userId, "isbn")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> writer.register(command(), Map.of()))
        .isInstanceOf(IllegalStateException.class);

    verify(repository, never()).createUserBook(any());
    verify(repository, never()).createShelfBook(any());
  }

  @Test
  void 트랜잭션_진입_후_소유권_재확인이_실패하면_등록하지_않는다() {
    ShelfException failure = new ShelfException(ShelfErrorCode.SHELF_NOT_FOUND);
    when(shelfGetUseCase.get(shelfId, userId)).thenThrow(failure);

    assertThatThrownBy(() -> writer.register(command(), Map.of())).isSameAs(failure);

    verifyNoInteractions(repository);
  }

  private ShelfBookAddCommand command() {
    return new ShelfBookAddCommand(
        userId, shelfId, List.of(new ShelfBookAddCommand.BookInput("isbn", ReadingStatus.READ)));
  }

  private UserBook existing() {
    return new UserBook(
        UUID.randomUUID(),
        userId,
        "isbn",
        "기존 제목",
        "저자",
        "출판사",
        "cover",
        ReadingStatus.READING,
        Instant.parse("2026-10-01T00:00:00Z"),
        Instant.parse("2026-10-02T00:00:00Z"));
  }
}
