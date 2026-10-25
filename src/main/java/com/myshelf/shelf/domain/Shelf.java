package com.myshelf.shelf.domain;

import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class Shelf {
  private final UUID id;
  private final String name;
  private final String description;
  private final UUID userId;
}
