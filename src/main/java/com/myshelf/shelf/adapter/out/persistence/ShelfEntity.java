package com.myshelf.shelf.adapter.out.persistence;

import com.myshelf.shelf.domain.Shelf;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static ShelfEntity from(Shelf shelf) {
    return new ShelfEntity(
        shelf.getId(),
        shelf.getName(),
        shelf.getDescription(),
        shelf.getUserId(),
        shelf.getCreatedAt(),
        shelf.getUpdatedAt());
  }

  public static Shelf toDomain(ShelfEntity savedShelf) {
    return new Shelf(
        savedShelf.getId(),
        savedShelf.getName(),
        savedShelf.getDescription(),
        savedShelf.getUserId(),
        savedShelf.getCreatedAt(),
        savedShelf.getUpdatedAt());
  }
}
