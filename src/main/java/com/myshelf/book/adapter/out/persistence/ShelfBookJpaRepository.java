package com.myshelf.book.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShelfBookJpaRepository extends JpaRepository<ShelfBookEntity, UUID> {}
