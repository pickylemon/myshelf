package com.myshelf.book.application.port.in.shelf;

import com.myshelf.book.application.dto.ShelfBookItem;
import java.util.List;
import java.util.UUID;

public interface ShelfBookListUseCase {
  List<ShelfBookItem> list(UUID shelfId, UUID userId);
}
