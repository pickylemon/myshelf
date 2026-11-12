package com.myshelf.book.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShelfBookJpaRepository extends JpaRepository<ShelfBookEntity, UUID> {
  /*
  SELECT
      ub.isbn,
      jsonb_agg(
          jsonb_build_object(
              'shelfId', s.id,
              'shelfName', s.name
          )
      ) AS shelves
  FROM shelf s
  JOIN shelf_book sb
      ON sb.shelf_id = s.id
  JOIN user_book ub
      ON ub.id = sb.user_book_id
  WHERE ub.isbn IN (:isbns)
    AND ub.user_id = :userId
    AND s.user_id = :userId
  GROUP BY ub.isbn;
   */
  @Query(
      """
        SELECT ub.isbn AS isbn, s.id AS shelfId, s.name AS shelfName
        FROM ShelfBookEntity sb
        JOIN sb.userBook ub
        JOIN sb.shelf s
        WHERE ub.isbn IN :isbns
            AND ub.userId = :userId
            AND s.userId = :userId
    """)
  List<IsbnShelfRow> findRegisteredShelves(UUID userId, List<String> isbns);

  boolean existsByUserBookId(UUID userBookId);

  boolean existsByShelfIdAndUserBookId(UUID shelfId, UUID userBookId);

  @Query(
      """
        SELECT sb
        FROM ShelfBookEntity sb
        WHERE sb.id = :shelfBookId
        AND sb.shelf.userId = :userId
    """)
  Optional<ShelfBookEntity> findByIdAndUserId(UUID shelfBookId, UUID userId);

  @Query(
      """
        SELECT sb
        FROM ShelfBookEntity sb
        WHERE sb.id IN :shelfBookIds
        AND sb.shelf.userId = :userId
    """)
  List<ShelfBookEntity> findAllByIdAndUserId(List<UUID> shelfBookIds, UUID userId);

  @Query(
      """
        SELECT sb
        FROM ShelfBookEntity sb
        JOIN FETCH sb.userBook
        WHERE sb.shelf.id = :shelfId
        AND sb.shelf.userId = :userId
        ORDER BY CASE WHEN sb.position IS NOT NULL THEN 0 ELSE 1 END,
              sb.position ASC,
              sb.updatedAt DESC,
              sb.id DESC
      """)
  List<ShelfBookEntity> findAllByShelfIdAndUserId(UUID shelfId, UUID userId);
}
