package com.myshelf.shelf.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShelfJpaRepository extends JpaRepository<ShelfEntity, UUID> {
  boolean existsByUserIdAndName(UUID userId, String name);

  Optional<ShelfEntity> findByIdAndUserId(UUID shelfId, UUID userId);

  boolean existsByUserIdAndNameAndIdNot(UUID userId, String name, UUID shelfId);

  List<ShelfEntity> findShelfEntitiesByUserId(UUID userId);
}
