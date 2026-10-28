package com.myshelf.shelf.application.port.in;

import java.util.UUID;

public interface ShelfDeleteUseCase {
  void delete(UUID shelfId, UUID userId);
}
