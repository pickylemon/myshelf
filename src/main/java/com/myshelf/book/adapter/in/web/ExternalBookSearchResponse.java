package com.myshelf.book.adapter.in.web;

public record ExternalBookSearchResponse(
    ExternalBookResponse externalBookResponse, int page, int pageSize, long totalCount) {}
