package com.myshelf.shelf.application.port.out;

import com.myshelf.shelf.domain.Shelf;
import java.util.UUID;

public interface ShelfRepositoryPort {
  Shelf save(Shelf shelf);

  boolean existsByUserIdAndName(UUID userId, String name);
}
