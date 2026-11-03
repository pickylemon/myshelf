package com.myshelf.book.adapter.out.persistence;

import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.domain.UserBook;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "user_books")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserBookEntity {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "isbn", nullable = false)
  private String isbn;

  @Column(name = "title", nullable = false)
  private String title;

  @Column(name = "author")
  private String author;

  @Column(name = "publisher")
  private String publisher;

  @Column(name = "cover_image_url")
  private String coverImageUrl;

  @Enumerated(EnumType.STRING)
  @Column(name = "reading_status_code", nullable = false)
  private ReadingStatus readingStatus;

  @Column(name = "memo")
  private String memo;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static UserBookEntity from(UserBook userBook) {
    return new UserBookEntity(
        userBook.id(),
        userBook.userId(),
        userBook.isbn(),
        userBook.title(),
        userBook.author(),
        userBook.publisher(),
        userBook.coverImageUrl(),
        userBook.readingStatus(),
        userBook.memo(),
        userBook.createdAt(),
        userBook.updatedAt());
  }

  public static UserBook toDomain(UserBookEntity entity) {
    return new UserBook(
        entity.getId(),
        entity.getUserId(),
        entity.getIsbn(),
        entity.getTitle(),
        entity.getAuthor(),
        entity.getPublisher(),
        entity.getCoverImageUrl(),
        entity.getReadingStatus(),
        entity.getMemo(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  public void updateReadingStatus(ReadingStatus readingStatus) {
    this.readingStatus = readingStatus;
  }

  public void resetMemo() {
    this.memo = null;
  }
}
