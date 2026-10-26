package com.myshelf.shelf.application.port.out;

import com.myshelf.shelf.domain.Shelf;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShelfRepositoryPort {
  Shelf save(Shelf shelf);

  boolean existsByUserIdAndName(UUID userId, String name);

  List<Shelf> list(UUID userId);

  Optional<Shelf> get(UUID userId, UUID shelfId);
}
