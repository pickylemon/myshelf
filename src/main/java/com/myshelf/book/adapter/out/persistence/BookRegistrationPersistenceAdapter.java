package com.myshelf.book.adapter.out.persistence;

import com.myshelf.book.application.dto.BookShelfInfo;
import com.myshelf.book.application.port.out.registration.BookRegistrationLookupPort;
import com.myshelf.book.application.port.out.registration.BookRepositoryPort;
import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.domain.ShelfBook;
import com.myshelf.book.domain.UserBook;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import com.myshelf.global.persistence.ConstraintViolationMatcher;
import com.myshelf.shelf.adapter.out.persistence.ShelfEntity;
import com.myshelf.shelf.adapter.out.persistence.ShelfJpaRepository;
import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class BookRegistrationPersistenceAdapter
    implements BookRegistrationLookupPort, BookRepositoryPort {
  private static final String USER_BOOK_UNIQUE_CONSTRAINT = "uk_user_books_user_isbn";
  private static final String SHELF_BOOK_UNIQUE_CONSTRAINT = "uk_shelf_books_shelf_user_book";

  private final ShelfBookJpaRepository shelfBookJpaRepository;
  private final UserBookJpaRepository userBookJpaRepository;
  private final ShelfJpaRepository shelfJpaRepository;

  @Override
  public Map<String, List<BookShelfInfo>> lookup(UUID userId, List<String> isbnList) {
    Map<String, List<BookShelfInfo>> shelvesGroupByIsbn = new HashMap<>();
    List<IsbnShelfRow> registeredShelves =
        shelfBookJpaRepository.findRegisteredShelves(userId, isbnList);
    for (IsbnShelfRow row : registeredShelves) {
      String isbn = row.getIsbn();
      List<BookShelfInfo> shelves =
          shelvesGroupByIsbn.computeIfAbsent(isbn, key -> new ArrayList<>());
      shelves.add(new BookShelfInfo(row.getShelfId(), row.getShelfName()));
    }

    return shelvesGroupByIsbn;
  }

  @Override
  public Optional<UserBook> findUserBookByUserIdAndIsbn(UUID userId, String isbn) {
    return userBookJpaRepository.getByUserIdAndIsbn(userId, isbn).map(UserBookEntity::toDomain);
  }

  @Override
  public boolean existsShelfBookByUserBookId(UUID userBookId) {

    return shelfBookJpaRepository.existsByUserBookId(userBookId);
  }

  @Override
  public boolean existsByShelfIdAndUserBookId(UUID shelfId, UUID userBookId) {
    return shelfBookJpaRepository.existsByShelfIdAndUserBookId(shelfId, userBookId);
  }

  @Override
  public UserBook createUserBook(UserBook userBook) {
    try {
      UserBookEntity userBookEntity = UserBookEntity.from(userBook);
      UserBookEntity saved = userBookJpaRepository.saveAndFlush(userBookEntity);
      return UserBookEntity.toDomain(saved);
    } catch (DataIntegrityViolationException e) {
      if (isDuplicateRegistration(e)) {
        throw new BookException(BookErrorCode.DUPLICATE_REGISTRATION, e);
      }
      throw e;
    }
  }

  @Override
  public UserBook updateReadingStatus(UUID userBookId, UUID userId, ReadingStatus readingStatus) {
    UserBookEntity found =
        userBookJpaRepository
            .findByIdAndUserId(userBookId, userId)
            .orElseThrow(() -> new BookException(BookErrorCode.USER_BOOK_NOT_FOUND));

    found.updateReadingStatus(readingStatus);

    return UserBookEntity.toDomain(found);
  }

  @Override
  public UserBook resetForReregistration(UUID userBookId, UUID userId) {
    UserBookEntity found =
        userBookJpaRepository
            .findByIdAndUserId(userBookId, userId)
            .orElseThrow(() -> new BookException(BookErrorCode.USER_BOOK_NOT_FOUND));

    found.resetMemo();

    return UserBookEntity.toDomain(found);
  }

  @Override
  public ShelfBook createShelfBook(ShelfBook shelfBook) {
    UserBookEntity userBook =
        userBookJpaRepository
            .findById(shelfBook.userBookId())
            .orElseThrow(() -> new BookException(BookErrorCode.USER_BOOK_NOT_FOUND));

    ShelfEntity shelf =
        shelfJpaRepository
            .findById(shelfBook.shelfId())
            .orElseThrow(() -> new ShelfException(ShelfErrorCode.SHELF_NOT_FOUND));

    ShelfBookEntity shelfBookEntity = ShelfBookEntity.from(shelfBook, shelf, userBook);

    try {
      ShelfBookEntity saved = shelfBookJpaRepository.saveAndFlush(shelfBookEntity);
      return ShelfBookEntity.toDomain(saved);
    } catch (DataIntegrityViolationException e) {
      if (isDuplicateRegistration(e)) {
        throw new BookException(BookErrorCode.DUPLICATE_REGISTRATION, e);
      }
      throw e;
    }
  }

  private boolean isDuplicateRegistration(Throwable exception) {
    return ConstraintViolationMatcher.matches(
            exception, ConstraintKind.UNIQUE, USER_BOOK_UNIQUE_CONSTRAINT)
        || ConstraintViolationMatcher.matches(
            exception, ConstraintKind.UNIQUE, SHELF_BOOK_UNIQUE_CONSTRAINT);
  }
}
