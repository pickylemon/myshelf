package com.myshelf.book.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBookJpaRepository extends JpaRepository<UserBookEntity, UUID> {

  Optional<UserBookEntity> getByUserIdAndIsbn(UUID userId, String isbn);

  Optional<UserBookEntity> findByIdAndUserId(UUID userBookId, UUID userId);
}
