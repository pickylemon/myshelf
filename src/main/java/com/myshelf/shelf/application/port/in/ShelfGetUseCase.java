package com.myshelf.shelf.application.port.in;

import com.myshelf.shelf.domain.Shelf;
import java.util.UUID;

public interface ShelfGetUseCase {
  Shelf get(UUID userId, UUID shelfId);
}
