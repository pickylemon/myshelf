package com.myshelf.book.application.service;

import com.myshelf.book.application.dto.AddedBook;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.port.in.registration.ShelfBookAddCommand;
import com.myshelf.book.application.port.in.registration.ShelfBookAddUseCase;
import com.myshelf.book.application.port.in.shelf.ShelfBookRemoveUseCase;
import com.myshelf.book.application.port.out.BookMetadataCachePort;
import com.myshelf.book.application.port.out.BookRepositoryPort;
import com.myshelf.book.application.port.out.ExternalBookSearchPort;
import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.domain.ShelfBook;
import com.myshelf.book.domain.UserBook;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import com.myshelf.global.util.UuidGenerator;
import com.myshelf.shelf.application.port.in.ShelfGetUseCase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookService implements ShelfBookAddUseCase, ShelfBookRemoveUseCase {
  private final ExternalBookSearchPort externalBookSearchPort;
  private final BookMetadataCachePort bookMetadataCachePort;
  private final ShelfGetUseCase shelfGetUseCase;
  private final BookRepositoryPort bookRepositoryPort;

  @Override
  public List<AddedBook> add(ShelfBookAddCommand command) {
    // 1. 유효성 검증(해당 shelf가 user소유인지)
    shelfGetUseCase.get(command.shelfId(), command.userId());

    List<ShelfBookAddCommand.BookInput> requestedBooks = command.books();
    List<String> requestIsbns =
        requestedBooks.stream().map(ShelfBookAddCommand.BookInput::isbn).toList();

    // - 2. UserBook 조회
    List<String> isbnsToRegister = new ArrayList<>();
    for (String isbn : requestIsbns) {
      Optional<UserBook> userBookByUserIdAndIsbn =
          bookRepositoryPort.findUserBookByUserIdAndIsbn(command.userId(), isbn);
      if (userBookByUserIdAndIsbn.isEmpty()) {
        isbnsToRegister.add(isbn);
      }
    }

    // 2. 신규 등록할 Book정보 조회
    Map<String, BookSearchMeta> requestedBookMetaMap = new HashMap<>();
    isbnsToRegister.forEach(
        isbn -> {
          BookSearchMeta bookSearchMeta = resolveNewBookMetadata(isbn);
          requestedBookMetaMap.put(isbn, bookSearchMeta);
        });

    // 3. 신규 Book 저장 (UserBook, ShelfBook)
    isbnsToRegister.forEach(
        isbn -> {
          BookSearchMeta bookSearchMeta = requestedBookMetaMap.get(isbn);
          ReadingStatus readingStatus =
              requestedBooks.stream()
                  .filter(book -> isbn.equals(book.isbn()))
                  .findFirst()
                  .get()
                  .readingStatus();

          UserBook userBook =
              new UserBook(
                  UuidGenerator.generate(),
                  command.userId(),
                  isbn,
                  bookSearchMeta.title(),
                  bookSearchMeta.author(),
                  bookSearchMeta.publisher(),
                  bookSearchMeta.coverImageUrl(),
                  readingStatus);

          ShelfBook shelfBook =
              new ShelfBook(UuidGenerator.generate(), command.shelfId(), userBook.id());

          bookRepositoryPort.createUserBook(userBook);
          bookRepositoryPort.createShelfBook(shelfBook);
        });

    List<String> isbnsToUpdate =
        requestIsbns.stream().filter(isbn -> !isbnsToRegister.contains(isbn)).toList();

    // - 2. 현재 서재가 아닌 다른 서재에 등록된 책이라면 업데이트
    List<UserBook> booksToUpdate =
        isbnsToUpdate.stream()
            .filter(
                isbn ->
                    !bookRepositoryPort.existsShelfBookByShelfIdAndIsbn(command.shelfId(), isbn))
            .map(
                isbn ->
                    bookRepositoryPort
                        .findUserBookByUserIdAndIsbn(command.userId(), isbn)
                        .orElseThrow(() -> new BookException(BookErrorCode.USER_BOOK_NOT_FOUND)))
            .toList();

    booksToUpdate.forEach(
        book -> {
          ReadingStatus readingStatus =
              requestedBooks.stream()
                  .filter(requestedBook -> requestedBook.isbn().equals(book.isbn()))
                  .findFirst()
                  .get()
                  .readingStatus();
          bookRepositoryPort.updateReadingStatus(book.id(), command.userId(), readingStatus);

          ShelfBook shelfBook =
              new ShelfBook(UuidGenerator.generate(), command.shelfId(), book.id());
          bookRepositoryPort.createShelfBook(shelfBook);
        });

    // -3. 연결없음: 메모 초기화
    /*
    SELECT u.isbn
    FROM user_books u
    WHERE u.user_id = @user_id
    AND u.isbn IN ANY(~)
    AND NOT EXISTS (
      SELECT 1
      FROM shelf_books s
      WHERE s.user_book_id = u.id
    );
     */

    return null;
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
