package com.myshelf.book.adapter.out.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NationalLibraryBookResponse(
    @JsonProperty("EA_ISBN") String isbn,
    @JsonProperty("TITLE") String title,
    @JsonProperty("AUTHOR") String author,
    @JsonProperty("PUBLISHER") String publisher,
    @JsonProperty("PUBLISH_PREDATE") LocalDate publicationDate,
    @JsonProperty("TITLE_URL") String coverImageUrl) {}
