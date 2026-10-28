package com.myshelf.shelf.application.port.out;

import com.myshelf.shelf.domain.Shelf;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShelfRepositoryPort {
  Shelf save(Shelf shelf);

  Optional<Shelf> findByIdAndUserId(UUID shelfId, UUID userId);

  boolean existsByUserIdAndName(UUID userId, String name);

  boolean existsByUserIdAndNameExcludingSelf(Shelf shelf);

  List<Shelf> list(UUID userId);

  Optional<Shelf> update(Shelf shelf);

  boolean delete(UUID shelfId, UUID userId);
}
