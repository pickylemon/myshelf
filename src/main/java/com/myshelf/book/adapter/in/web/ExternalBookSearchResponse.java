package com.myshelf.book.adapter.in.web;

import com.myshelf.book.application.dto.ExternalBookSearchResult;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ExternalBookSearchResponse(
    List<ExternalBookResponse> books, boolean hasNext, UUID searchId) {
  public static @Nullable ExternalBookSearchResponse from(ExternalBookSearchResult searchResult) {
    List<ExternalBookResponse> bookResponses =
        searchResult.books().stream().map(ExternalBookResponse::from).toList();
    return new ExternalBookSearchResponse(
        bookResponses, searchResult.hasNext(), searchResult.searchId());
  }
}
