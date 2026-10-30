package com.myshelf.book.adapter.out.persistence;

import java.util.UUID;

public interface IsbnShelfRow {
  String getIsbn();

  UUID getShelfId();

  String getShelfName();
}
