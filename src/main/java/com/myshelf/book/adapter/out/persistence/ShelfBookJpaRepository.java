package com.myshelf.book.adapter.out.persistence;

import java.util.List;
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

  @Query(
      """
      SELECT CASE WHEN COUNT(sb) > 0 THEN true ELSE false END
      FROM ShelfBookEntity sb
      WHERE sb.shelf.id = :shelfId
      AND sb.userBook.isbn = :isbn
    """)
  boolean existsByShelfIdAndIsbn(UUID shelfId, String isbn);

  boolean existsByUserBookId(UUID userBookId);

  boolean existsByShelfIdAndUserBookId(UUID shelfId, UUID userBookId);
}
