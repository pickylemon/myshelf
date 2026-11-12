package com.myshelf.book.adapter.out.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NationalLibrarySearchResponse(
    @JsonProperty("TOTAL_COUNT") int totalCount,
    @JsonProperty("docs") List<NationalLibraryBookResponse> books) {}
