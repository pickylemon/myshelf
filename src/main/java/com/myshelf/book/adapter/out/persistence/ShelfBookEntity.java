package com.myshelf.book.adapter.out.persistence;

import com.myshelf.shelf.adapter.out.persistence.ShelfEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "shelf_books")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShelfBookEntity {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_book_id", nullable = false)
  private UserBookEntity userBook;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "shelf_id", nullable = false)
  private ShelfEntity shelf;
}
