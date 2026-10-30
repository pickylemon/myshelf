package com.myshelf.book.adapter.out.persistence;

import com.myshelf.book.application.dto.BookShelfInfo;
import com.myshelf.book.application.port.out.BookRegistrationLookupPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class BookRegistrationPersistenceAdapter implements BookRegistrationLookupPort {
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
}
