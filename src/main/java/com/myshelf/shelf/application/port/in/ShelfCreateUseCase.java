package com.myshelf.shelf.application.port.in;

import java.util.UUID;

public interface ShelfCreateUseCase {
  UUID create(ShelfCreateCommand command);
}
