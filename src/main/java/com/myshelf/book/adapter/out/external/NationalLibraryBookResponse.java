package com.myshelf.book.adapter.out.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NationalLibraryBookResponse(
    @JsonProperty("EA_ISBN") String isbn,
    @JsonProperty("TITLE") String title,
    @JsonProperty("AUTHOR") String author,
    @JsonProperty("PUBLISHER") String publisher,
    @JsonProperty("PUBLISH_PREDATE") String publicationDate,
    @JsonProperty("TITLE_URL") String coverImageUrl) {}
