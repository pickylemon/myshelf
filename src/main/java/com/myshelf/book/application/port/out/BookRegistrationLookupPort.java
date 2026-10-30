package com.myshelf.book.application.port.out;

import com.myshelf.book.application.dto.BookShelfInfo;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface BookRegistrationLookupPort {
  Map<String, List<BookShelfInfo>> lookup(UUID userId, List<String> isbnList);
}
