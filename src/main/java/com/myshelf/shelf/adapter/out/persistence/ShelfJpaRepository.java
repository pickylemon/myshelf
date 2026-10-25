package com.myshelf.shelf.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShelfJpaRepository extends JpaRepository<ShelfEntity, UUID> {
  boolean existsByUserIdAndName(UUID userId, String name);
}
