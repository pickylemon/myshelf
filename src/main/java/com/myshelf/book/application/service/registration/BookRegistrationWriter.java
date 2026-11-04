package com.myshelf.book.application.service.registration;

import com.myshelf.book.application.dto.AddedBook;
import com.myshelf.book.application.dto.BookSearchMeta;
import com.myshelf.book.application.port.in.registration.ShelfBookAddCommand;
import com.myshelf.book.application.port.out.registration.BookRepositoryPort;
import com.myshelf.book.domain.ShelfBook;
import com.myshelf.book.domain.UserBook;
import com.myshelf.global.util.UuidGenerator;
import com.myshelf.shelf.application.port.in.ShelfGetUseCase;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BookRegistrationWriter {
  private final BookRepositoryPort bookRepositoryPort;
  private final ShelfGetUseCase shelfGetUseCase;

  @Transactional
  public List<AddedBook> register(
      ShelfBookAddCommand command, Map<String, BookSearchMeta> metadataByIsbn) {

    // 1. 소유권 재확인
    shelfGetUseCase.get(command.shelfId(), command.userId());

    List<AddedBook> addedBookList = new ArrayList<>();

    for (ShelfBookAddCommand.BookInput inputBook : command.books()) {
      Optional<UserBook> found =
          bookRepositoryPort.findUserBookByUserIdAndIsbn(command.userId(), inputBook.isbn());

      UserBook userBook;
      if (found.isPresent()) { // 도서상태 업데이트
        userBook = found.get();
        if (bookRepositoryPort.existsByShelfIdAndUserBookId(command.shelfId(), userBook.id()))
          continue; // 이미 해당 서재에 있는 도서는 필터링

        // 도서 상태 업데이트
        bookRepositoryPort.updateReadingStatus(
            userBook.id(), command.userId(), inputBook.readingStatus());

        // 서재에 없는 도서면 메모 초기화
        if (!bookRepositoryPort.existsShelfBookByUserBookId(userBook.id())) {
          bookRepositoryPort.resetForReregistration(userBook.id(), command.userId());
        }

      } else { // 사용자 신규 도서의 경우
        BookSearchMeta bookMeta = metadataByIsbn.get(inputBook.isbn());
        if (bookMeta == null) {
          throw new IllegalStateException("신규 등록에 필요한 도서 메타데이가 준비되지 않음");
        }

        userBook =
            new UserBook(
                UuidGenerator.generate(),
                command.userId(),
                inputBook.isbn(),
                bookMeta.title(),
                bookMeta.author(),
                bookMeta.publisher(),
                bookMeta.coverImageUrl(),
                inputBook.readingStatus());

        bookRepositoryPort.createUserBook(userBook);
      }

      // 공통
      ShelfBook shelfBook =
          new ShelfBook(UuidGenerator.generate(), command.shelfId(), userBook.id());
      ShelfBook createdShelfBook = bookRepositoryPort.createShelfBook(shelfBook);
      addedBookList.add(new AddedBook(inputBook.isbn(), createdShelfBook.id()));
    }

    return addedBookList;
  }
}
