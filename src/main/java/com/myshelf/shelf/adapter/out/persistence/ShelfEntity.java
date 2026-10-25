package com.myshelf.shelf.adapter.out.persistence;

import com.myshelf.shelf.domain.Shelf;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "shelves")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ShelfEntity {
  @Id private UUID id;

  @Column(nullable = false)
  private String name;

  private String description;

  @Column(nullable = false)
  private UUID userId;

  public static ShelfEntity from(Shelf shelf) {
    return new ShelfEntity(
        shelf.getId(), shelf.getName(), shelf.getDescription(), shelf.getUserId());
  }

  public static Shelf toDomain(ShelfEntity savedShelf) {
    return new Shelf(
        savedShelf.getId(),
        savedShelf.getName(),
        savedShelf.getDescription(),
        savedShelf.getUserId());
  }
}
