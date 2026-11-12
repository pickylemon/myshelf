package com.myshelf.book.application.port.in.shelf;

public interface ShelfBookRemoveUseCase {
  void delete(ShelfBookDeleteCommand deleteCommand);
}
