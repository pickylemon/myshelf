package com.myshelf.book.adapter.in.web.shelfBook;

import com.myshelf.global.util.DuplicateChecker;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record ShelfBookDeleteRequest(@NotNull @NotEmpty List<@NotNull UUID> shelfBookIds) {
  @AssertTrue(message = "중복된 도서가 있습니다.")
  public boolean isUnique() {
    if (shelfBookIds == null) return true;

    return DuplicateChecker.isUnique(this.shelfBookIds());
  }
}
