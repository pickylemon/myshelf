package com.myshelf.book.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.myshelf.book.application.dto.AddedBook;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.dto.ShelfBookItem;
import com.myshelf.book.application.port.in.reading.UserBookUpdateReadingStatusCommand;
import com.myshelf.book.application.port.in.registration.ShelfBookAddCommand;
import com.myshelf.book.application.port.in.shelf.ShelfBookDeleteCommand;
import com.myshelf.book.application.port.in.shelf.ShelfBookUpdateCommand;
import com.myshelf.book.application.port.out.metadata.BookMetadataCachePort;
import com.myshelf.book.application.port.out.registration.BookRepositoryPort;
import com.myshelf.book.application.port.out.search.ExternalBookSearchPort;
import com.myshelf.book.application.service.registration.BookRegistrationWriter;
import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.domain.ShelfBook;
import com.myshelf.book.domain.UserBook;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import com.myshelf.shelf.application.port.in.ShelfGetUseCase;
import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 외부 정보 준비와 Writer 위임을 검증한다. DB 트랜잭션은 통합 테스트의 책임이다. */
@ExtendWith(MockitoExtension.class)
class BookServiceTest {
  @Mock ExternalBookSearchPort externalBookSearchPort;
  @Mock BookMetadataCachePort metadataCache;
  @Mock ShelfGetUseCase shelfGetUseCase;
  @Mock BookRepositoryPort repository;
  @Mock BookRegistrationWriter writer;

  private BookService service;
  private UUID userId;
  private UUID shelfId;

  @BeforeEach
  void setUp() {
    service =
        new BookService(externalBookSearchPort, metadataCache, shelfGetUseCase, repository, writer);
    userId = UUID.randomUUID();
    shelfId = UUID.randomUUID();
  }

  // TODO(직접 작성): 삭제 목록에 타인·미존재 ID가 섞이면 예외를 던지고 삭제 Port를 호출하지 않는다.

  /** 캐시 부재 시 외부 메타데이터를 저장하고 확보한 값으로 Writer를 호출한다. */
  @Test
  void 외부_조회에_성공하면_캐시에_저장하고_Writer의_결과를_반환한다() {
    ShelfBookAddCommand command = command("new");
    BookSearchMeta metadata = metadata("new");
    when(metadataCache.findByIsbn("new")).thenReturn(Optional.empty());
    when(externalBookSearchPort.findByIsbn("new")).thenReturn(Optional.of(metadata));
    List<AddedBook> expected = List.of(new AddedBook("new", UUID.randomUUID()));
    when(writer.register(command, Map.of("new", metadata))).thenReturn(expected);

    assertThat(service.add(command)).isSameAs(expected);

    InOrder order = org.mockito.Mockito.inOrder(externalBookSearchPort, metadataCache, writer);
    order.verify(metadataCache).findByIsbn("new");
    order.verify(externalBookSearchPort).findByIsbn("new");
    order.verify(metadataCache).save(metadata);
    order.verify(writer).register(command, Map.of("new", metadata));
  }

  /** 소유권을 확인한 연결의 ID와 요청 메모만 변경 Port에 전달한다. */
  @Test
  void 메모_변경은_소유권_조회_후_전달한다() {
    UUID linkId = UUID.randomUUID();
    when(repository.findShelfBookByIdAndUserId(linkId, userId))
        .thenReturn(new ShelfBook(linkId, shelfId, UUID.randomUUID()));

    service.updateMemo(new ShelfBookUpdateCommand(userId, linkId, "메모"));

    InOrder order = org.mockito.Mockito.inOrder(repository);
    order.verify(repository).findShelfBookByIdAndUserId(linkId, userId);
    order.verify(repository).updateShelfBookMemo(linkId, "메모");
  }

  /** 메모 대상의 소유권을 확인하지 못하면 변경을 요청하지 않는다. */
  @Test
  void 메모_대상이_없으면_변경하지_않는다() {
    UUID linkId = UUID.randomUUID();
    BookException failure = new BookException(BookErrorCode.SHELF_BOOK_NOT_FOUND);
    when(repository.findShelfBookByIdAndUserId(linkId, userId)).thenThrow(failure);

    assertThatThrownBy(() -> service.updateMemo(new ShelfBookUpdateCommand(userId, linkId, null)))
        .isSameAs(failure);
    verify(repository, never()).updateShelfBookMemo(any(), any());
  }

  /** 모든 요청 ID를 소유권 조건으로 확인한 뒤 원래 ID 목록을 삭제 Port에 전달한다. */
  @Test
  void 다건_삭제는_모든_대상을_확인한_뒤_수행한다() {
    UUID bookId = UUID.randomUUID();
    List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
    when(repository.findAllShelfBookByIdAndUserId(ids, userId))
        .thenReturn(
            List.of(
                new ShelfBook(ids.get(0), shelfId, bookId),
                new ShelfBook(ids.get(1), UUID.randomUUID(), bookId)));

    service.delete(new ShelfBookDeleteCommand(userId, ids));

    InOrder order = org.mockito.Mockito.inOrder(repository);
    order.verify(repository).findAllShelfBookByIdAndUserId(ids, userId);
    order.verify(repository).deleteShelfBooks(ids);
  }

  /** 상태 변경은 사용자 범위로 제한한 Port에 맡기며 외부 메타데이터는 조회하지 않는다. */
  @Test
  void 독서_상태는_사용자_ID와_함께_변경_Port에_전달한다() {
    UUID bookId = UUID.randomUUID();
    service.update(new UserBookUpdateReadingStatusCommand(userId, bookId, ReadingStatus.READ));
    verify(repository).updateReadingStatus(bookId, userId, ReadingStatus.READ);
    verifyNoInteractions(metadataCache, externalBookSearchPort, writer);
  }

  /** 정상 빈 서재를 반환하기 전에도 Shelf 자체의 존재·소유권을 먼저 확인한다. */
  @Test
  void 목록은_서재를_확인한_뒤_조회하며_빈_목록을_유지한다() {
    List<ShelfBookItem> expected = List.of();
    when(repository.findAllShelfBooksByShelfIdAndUserId(shelfId, userId)).thenReturn(expected);

    assertThat(service.list(shelfId, userId)).isSameAs(expected);

    InOrder order = org.mockito.Mockito.inOrder(shelfGetUseCase, repository);
    order.verify(shelfGetUseCase).get(shelfId, userId);
    order.verify(repository).findAllShelfBooksByShelfIdAndUserId(shelfId, userId);
  }

  /** 없는·타인 소유 서재는 빈 도서 목록으로 바꾸지 않고 404용 예외를 유지한다. */
  @Test
  void 서재를_찾지_못하면_목록_Port를_호출하지_않는다() {
    ShelfException failure = new ShelfException(ShelfErrorCode.SHELF_NOT_FOUND);
    when(shelfGetUseCase.get(shelfId, userId)).thenThrow(failure);
    assertThatThrownBy(() -> service.list(shelfId, userId)).isSameAs(failure);
    verifyNoInteractions(repository);
  }

  @Test
  void 기존_도서는_재사용하고_신규_도서만_캐시에서_준비한다() {
    ShelfBookAddCommand command = command("existing", "new");
    UserBook existing =
        new UserBook(
            UUID.randomUUID(), userId, "existing", "기존", null, null, null, ReadingStatus.READING);
    BookSearchMeta metadata = metadata("new");
    when(repository.findUserBookByUserIdAndIsbn(userId, "existing"))
        .thenReturn(Optional.of(existing));
    when(repository.findUserBookByUserIdAndIsbn(userId, "new")).thenReturn(Optional.empty());
    when(metadataCache.findByIsbn("new")).thenReturn(Optional.of(metadata));
    List<AddedBook> expected = List.of(new AddedBook("new", UUID.randomUUID()));
    when(writer.register(command, Map.of("new", metadata))).thenReturn(expected);

    assertThat(service.add(command)).isSameAs(expected);

    verify(shelfGetUseCase).get(shelfId, userId);
    verify(metadataCache).findByIsbn("new");
    verify(metadataCache, never()).findByIsbn("existing");
    verify(metadataCache, never()).save(any());
    verifyNoInteractions(externalBookSearchPort);
    verify(writer).register(command, Map.of("new", metadata));
  }

  @Test
  void 기존_도서만_요청하면_메타데이터_조회_없이_Writer를_호출한다() {
    ShelfBookAddCommand command = command("existing");
    when(repository.findUserBookByUserIdAndIsbn(userId, "existing"))
        .thenReturn(
            Optional.of(
                new UserBook(
                    UUID.randomUUID(),
                    userId,
                    "existing",
                    "기존",
                    null,
                    null,
                    null,
                    ReadingStatus.READ)));
    when(writer.register(command, Map.of())).thenReturn(List.of());

    assertThat(service.add(command)).isEmpty();

    verify(writer).register(command, Map.of());
    verifyNoInteractions(metadataCache, externalBookSearchPort);
  }

  @Test
  void 외부에서_신규_도서를_찾지_못하면_등록하지_않는다() {
    when(repository.findUserBookByUserIdAndIsbn(userId, "missing")).thenReturn(Optional.empty());
    when(metadataCache.findByIsbn("missing")).thenReturn(Optional.empty());
    when(externalBookSearchPort.findByIsbn("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.add(command("missing")))
        .isInstanceOfSatisfying(
            BookException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.BOOK_METADATA_NOT_FOUND));

    verifyNoInteractions(writer);
    verify(metadataCache, never()).save(any());
  }

  @Test
  void 캐시_조회_장애는_외부_조회로_우회하지_않는다() {
    BookException failure = new BookException(BookErrorCode.BOOK_METADATA_CACHE_UNAVAILABLE);
    when(repository.findUserBookByUserIdAndIsbn(userId, "new")).thenReturn(Optional.empty());
    when(metadataCache.findByIsbn("new")).thenThrow(failure);

    assertThatThrownBy(() -> service.add(command("new"))).isSameAs(failure);

    verifyNoInteractions(externalBookSearchPort, writer);
  }

  @Test
  void 외부에서_확보한_정보의_캐시_저장만_실패하면_등록을_계속한다() {
    ShelfBookAddCommand command = command("new");
    BookSearchMeta metadata = metadata("new");
    when(repository.findUserBookByUserIdAndIsbn(userId, "new")).thenReturn(Optional.empty());
    when(metadataCache.findByIsbn("new")).thenReturn(Optional.empty());
    when(externalBookSearchPort.findByIsbn("new")).thenReturn(Optional.of(metadata));
    doThrow(new BookException(BookErrorCode.BOOK_METADATA_CACHE_UNAVAILABLE))
        .when(metadataCache)
        .save(metadata);
    when(writer.register(command, Map.of("new", metadata))).thenReturn(List.of());

    assertThat(service.add(command)).isEmpty();

    verify(writer).register(command, Map.of("new", metadata));
  }

  @Test
  void 서재_소유권_검증이_실패하면_정보_준비도_하지_않는다() {
    ShelfException failure = new ShelfException(ShelfErrorCode.SHELF_NOT_FOUND);
    when(shelfGetUseCase.get(shelfId, userId)).thenThrow(failure);

    assertThatThrownBy(() -> service.add(command("new"))).isSameAs(failure);

    verifyNoInteractions(repository, metadataCache, externalBookSearchPort, writer);
  }

  private ShelfBookAddCommand command(String... isbns) {
    return new ShelfBookAddCommand(
        userId,
        shelfId,
        Arrays.stream(isbns)
            .map(isbn -> new ShelfBookAddCommand.BookInput(isbn, ReadingStatus.READ))
            .toList());
  }

  /** 앞 도서의 메타데이터를 확보했어도 뒤 도서가 없으면 등록 Writer를 시작하지 않는다. */
  @Test
  void 다건_메타데이터_중_하나를_찾지_못하면_전체_등록을_시작하지_않는다() {
    BookSearchMeta first = metadata("first");
    when(metadataCache.findByIsbn("first")).thenReturn(Optional.of(first));
    when(metadataCache.findByIsbn("missing")).thenReturn(Optional.empty());
    when(externalBookSearchPort.findByIsbn("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.add(command("first", "missing")))
        .isInstanceOfSatisfying(
            BookException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(BookErrorCode.BOOK_METADATA_NOT_FOUND));

    verifyNoInteractions(writer);
  }

  private BookSearchMeta metadata(String isbn) {
    return new BookSearchMeta(isbn, "도서", "저자", "출판사", null, null);
  }
}
