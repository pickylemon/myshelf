package com.myshelf.book.adapter.out.persistence;

import com.myshelf.book.domain.ShelfBook;
import com.myshelf.shelf.adapter.out.persistence.ShelfEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "shelf_books")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ShelfBookEntity {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_book_id", nullable = false)
  private UserBookEntity userBook;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "shelf_id", nullable = false)
  private ShelfEntity shelf;

  @Column(name = "position")
  private Integer position;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static ShelfBookEntity from(
      ShelfBook shelfBook, ShelfEntity shelf, UserBookEntity userBook) {
    return new ShelfBookEntity(
        shelfBook.id(), userBook, shelf, shelfBook.position(), shelfBook.createdAt());
  }

  public static ShelfBook toDomain(ShelfBookEntity entity) {
    return new ShelfBook(
        entity.getId(),
        entity.getShelf().getId(),
        entity.getUserBook().getId(),
        entity.getPosition(),
        entity.getCreatedAt());
  }
}
