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
import com.myshelf.book.application.port.in.registration.ShelfBookAddCommand;
import com.myshelf.book.application.port.out.metadata.BookMetadataCachePort;
import com.myshelf.book.application.port.out.registration.BookRepositoryPort;
import com.myshelf.book.application.port.out.search.ExternalBookSearchPort;
import com.myshelf.book.application.service.registration.BookRegistrationWriter;
import com.myshelf.book.domain.ReadingStatus;
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

  // TODO(테스트 구현예졍): 캐시가 비었을 때 외부 ISBN 조회 → 캐시 저장 → 확보한 정보를 Writer에 전달하는 정상 흐름.
  // 외부 API와 캐시 저장 호출의 ISBN·값을 검증하고 Writer의 결과가 그대로 반환되는지 확인한다.

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

  private BookSearchMeta metadata(String isbn) {
    return new BookSearchMeta(isbn, "도서", "저자", "출판사", null, null);
  }
}
