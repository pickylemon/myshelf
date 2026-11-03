package com.myshelf.book.adapter.out.persistence;

import com.myshelf.book.application.dto.BookShelfInfo;
import com.myshelf.book.application.port.out.registration.BookRegistrationLookupPort;
import com.myshelf.book.application.port.out.registration.BookRepositoryPort;
import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.domain.ShelfBook;
import com.myshelf.book.domain.UserBook;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class BookRegistrationPersistenceAdapter
    implements BookRegistrationLookupPort, BookRepositoryPort {
  private final ShelfBookJpaRepository shelfBookJpaRepository;

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
    return Optional.empty();
  }

  @Override
  public boolean exitsShelfBookByShelfIdAndIsbn(UUID shelfId, String isbn) {
    return false;
  }

  @Override
  public boolean existsShelfBookByShelfIdAndIsbn(UUID shelfId, String isbn) {
    return false;
  }

  @Override
  public boolean existsShelfBookByUserBookId(UUID userBookId) {
    return false;
  }

  @Override
  public boolean existsShelfBookByShelfIdAndUserBookId(UUID shelfId, UUID userBookId) {
    return false;
  }

  @Override
  public Optional<Integer> findMaxPositionByShelfId(UUID shelfId) {
    return Optional.empty();
  }

  @Override
  public UserBook createUserBook(UserBook userBook) {
    return null;
  }

  @Override
  public UserBook updateReadingStatus(UUID userBookId, UUID userId, ReadingStatus readingStatus) {
    return null;
  }

  @Override
  public UserBook resetForReregistration(UUID userBookId, UUID userId) {
    return null;
  }

  @Override
  public ShelfBook createShelfBook(ShelfBook shelfBook) {
    return null;
  }
}
