package com.myshelf.shelf.application.port.in;

import com.myshelf.shelf.domain.Shelf;
import java.util.List;
import java.util.UUID;

public interface ShelfListUseCase {
  List<Shelf> list(UUID userId);
}
