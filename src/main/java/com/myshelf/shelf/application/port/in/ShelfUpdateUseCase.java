package com.myshelf.shelf.application.port.in;

import com.myshelf.shelf.domain.Shelf;

public interface ShelfUpdateUseCase {
  Shelf update(ShelfUpdateCommand command);
}
