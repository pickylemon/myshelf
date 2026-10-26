package com.myshelf.shelf.application.service;

import com.myshelf.global.util.UuidGenerator;
import com.myshelf.shelf.application.port.in.ShelfCreateCommand;
import com.myshelf.shelf.application.port.in.ShelfCreateUseCase;
import com.myshelf.shelf.application.port.in.ShelfGetUseCase;
import com.myshelf.shelf.application.port.in.ShelfListUseCase;
import com.myshelf.shelf.application.port.out.ShelfRepositoryPort;
import com.myshelf.shelf.domain.Shelf;
import com.myshelf.shelf.error.ShelfErrorCode;
import com.myshelf.shelf.exception.ShelfException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ShelfService implements ShelfCreateUseCase, ShelfListUseCase, ShelfGetUseCase {
  private final ShelfRepositoryPort shelfRepositoryPort;

  @Override
  public UUID create(ShelfCreateCommand command) {
    // 1. name 중복 검사
    if (shelfRepositoryPort.existsByUserIdAndName(command.userId(), command.name())) {
      throw new ShelfException(ShelfErrorCode.DUPLICATE_NAME);
    }

    // 2. shelf 도메인 객체 생성
    UUID shelfId = UuidGenerator.generate(); // v7 UUID 생성
    Shelf shelf = new Shelf(shelfId, command.name(), command.description(), command.userId());

    // 4. 영속화
    return shelfRepositoryPort.save(shelf).getId();
  }

  @Override
  public List<Shelf> list(UUID userId) {
    return List.of();
  }

  @Override
  public Shelf get(UUID userId, UUID shelfId) {
    return null;
  }
}
