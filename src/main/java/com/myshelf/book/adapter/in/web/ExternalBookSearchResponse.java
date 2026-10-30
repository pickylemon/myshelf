package com.myshelf.book.adapter.in.web;

import com.myshelf.book.application.dto.ExternalBookSearchResult;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ExternalBookSearchResponse(List<ExternalBookResponse> books, boolean hasNext) {
  public static @Nullable ExternalBookSearchResponse from(ExternalBookSearchResult searchResult) {
    return null;
  }
}
