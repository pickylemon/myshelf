package com.myshelf.book.application.port.in.registration;

import com.myshelf.book.application.dto.AddedBook;
import java.util.List;

public interface ShelfBookAddUseCase {
  List<AddedBook> add(ShelfBookAddCommand command);
}
