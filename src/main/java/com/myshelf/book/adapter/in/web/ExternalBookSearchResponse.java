package com.myshelf.book.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalBookSearchResponse(List<ExternalBookResponse> books, int totalCount) {}
