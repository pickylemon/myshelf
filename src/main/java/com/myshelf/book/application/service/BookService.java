package com.myshelf.book.application.service;

import com.myshelf.book.application.dto.AddedBook;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.dto.ShelfBookItem;
import com.myshelf.book.application.port.in.reading.UserBookUpdateReadingStatusCommand;
import com.myshelf.book.application.port.in.reading.UserBookUpdateReadingStatusUseCase;
import com.myshelf.book.application.port.in.registration.ShelfBookAddCommand;
import com.myshelf.book.application.port.in.registration.ShelfBookAddUseCase;
import com.myshelf.book.application.port.in.shelf.ShelfBookDeleteCommand;
import com.myshelf.book.application.port.in.shelf.ShelfBookListUseCase;
import com.myshelf.book.application.port.in.shelf.ShelfBookRemoveUseCase;
import com.myshelf.book.application.port.in.shelf.ShelfBookUpdateCommand;
import com.myshelf.book.application.port.in.shelf.ShelfBookUpdateUseCase;
import com.myshelf.book.application.port.out.metadata.BookMetadataCachePort;
import com.myshelf.book.application.port.out.registration.BookRepositoryPort;
import com.myshelf.book.application.port.out.search.ExternalBookSearchPort;
import com.myshelf.book.application.service.registration.BookRegistrationWriter;
import com.myshelf.book.domain.ShelfBook;
import com.myshelf.book.domain.UserBook;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import com.myshelf.shelf.application.port.in.ShelfGetUseCase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookService
    implements ShelfBookAddUseCase,
        ShelfBookUpdateUseCase,
        ShelfBookRemoveUseCase,
        UserBookUpdateReadingStatusUseCase,
        ShelfBookListUseCase {
  private final ExternalBookSearchPort externalBookSearchPort;
  private final BookMetadataCachePort bookMetadataCachePort;
  private final ShelfGetUseCase shelfGetUseCase;
  private final BookRepositoryPort bookRepositoryPort;
  private final BookRegistrationWriter bookRegistrationWriter;

  @Override
  public List<AddedBook> add(ShelfBookAddCommand command) {
    // 1. 유효성 검증(해당 shelf가 user소유인지)
    shelfGetUseCase.get(command.shelfId(), command.userId());

    List<ShelfBookAddCommand.BookInput> requestedBooks = command.books();
    List<String> requestIsbns =
        requestedBooks.stream().map(ShelfBookAddCommand.BookInput::isbn).toList();

    // 2. 신규 등록할 도서 정보 조회
    List<String> isbnsToRegister = new ArrayList<>();
    for (String isbn : requestIsbns) {
      Optional<UserBook> userBookByUserIdAndIsbn =
          bookRepositoryPort.findUserBookByUserIdAndIsbn(command.userId(), isbn);
      if (userBookByUserIdAndIsbn.isEmpty()) {
        isbnsToRegister.add(isbn);
      }
    }

    Map<String, BookSearchMeta> requestedBookMetaMap = new HashMap<>();
    isbnsToRegister.forEach(
        isbn -> {
          BookSearchMeta bookSearchMeta = resolveNewBookMetadata(isbn);
          requestedBookMetaMap.put(isbn, bookSearchMeta);
        });

    // 3. 도서 신규 등록/독서상태 업데이트 및 서재 등록
    return bookRegistrationWriter.register(command, requestedBookMetaMap);
  }

  @Override
  @Transactional
  public void updateMemo(ShelfBookUpdateCommand command) {
    // ShelfBook을 찾는다.
    ShelfBook shelfBook =
        bookRepositoryPort.findShelfBookByIdAndUserId(command.shelfBookId(), command.userId());
    bookRepositoryPort.updateShelfBookMemo(shelfBook.id(), command.memo());
  }

  @Override
  @Transactional
  public void delete(ShelfBookDeleteCommand command) {
    List<ShelfBook> shelfBook =
        bookRepositoryPort.findAllShelfBookByIdAndUserId(command.shelfBookIds(), command.userId());

    if (shelfBook.size() != command.shelfBookIds().size()) {
      throw new BookException(BookErrorCode.SHELF_BOOK_NOT_FOUND);
    }

    bookRepositoryPort.deleteShelfBooks(command.shelfBookIds());
  }

  @Override
  @Transactional
  public void update(UserBookUpdateReadingStatusCommand command) {
    bookRepositoryPort.updateReadingStatus(
        command.userBookId(), command.userId(), command.readingStatus());
  }

  @Override
  @Transactional(readOnly = true)
  public List<ShelfBookItem> list(UUID shelfId, UUID userId) {
    // 1. shelf 소유권 확인 (내 서재에 책이 없는 경우(빈 목록)와 미존재·타인 소유 서재(404)를 구분)
    shelfGetUseCase.get(shelfId, userId);
    // 2. shelfBookItem(서재 내 등록된 도서 상세) 반환
    return bookRepositoryPort.findAllShelfBooksByShelfIdAndUserId(shelfId, userId);
  }

  private BookSearchMeta resolveNewBookMetadata(String isbn) {
    return bookMetadataCachePort
        .findByIsbn(isbn)
        .orElseGet(
            () -> {
              BookSearchMeta book =
                  externalBookSearchPort
                      .findByIsbn(isbn)
                      .orElseThrow(() -> new BookException(BookErrorCode.BOOK_METADATA_NOT_FOUND));

              cacheMetadataIfAvailable(book);
              return book;
            });
  }

  private void cacheMetadataIfAvailable(BookSearchMeta book) {
    try {
      bookMetadataCachePort.save(book);
    } catch (BookException e) {
      if (e.getErrorCode() != BookErrorCode.BOOK_METADATA_CACHE_UNAVAILABLE) {
        throw e;
      }
      log.warn("도서정보를 Redis 캐시에 저장하지 못했습니다. 오류 코드: {}", e.getErrorCode());
    }
  }
}
