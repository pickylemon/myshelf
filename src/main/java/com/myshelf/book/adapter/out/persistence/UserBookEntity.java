package com.myshelf.book.adapter.out.persistence;

import com.myshelf.book.domain.ReadingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_books")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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
}
