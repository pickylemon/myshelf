package com.myshelf.book.application.service;

import com.myshelf.book.application.dto.AddedBook;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.port.in.registration.ShelfBookAddCommand;
import com.myshelf.book.application.port.in.registration.ShelfBookAddUseCase;
import com.myshelf.book.application.port.in.shelf.ShelfBookRemoveUseCase;
import com.myshelf.book.application.port.out.BookMetadataCachePort;
import com.myshelf.book.application.port.out.ExternalBookSearchPort;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookService implements ShelfBookAddUseCase, ShelfBookRemoveUseCase {
  private final ExternalBookSearchPort externalBookSearchPort;
  private final BookMetadataCachePort bookMetadataCachePort;

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

  @Override
  public List<AddedBook> add(ShelfBookAddCommand command) {
    return List.of();
  }
}
