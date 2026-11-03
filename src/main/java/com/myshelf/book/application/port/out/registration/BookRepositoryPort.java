package com.myshelf.book.application.port.out.registration;

import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.domain.ShelfBook;
import com.myshelf.book.domain.UserBook;
import java.util.Optional;
import java.util.UUID;

public interface BookRepositoryPort {

  /** 사용자와 ISBN으로 기존 도서를 조회한다. 서재 연결이 없는 도서도 포함한다. */
  Optional<UserBook> findUserBookByUserIdAndIsbn(UUID userId, String isbn);

  boolean exitsShelfBookByShelfIdAndIsbn(UUID shelfId, String isbn);

  boolean existsShelfBookByShelfIdAndIsbn(UUID shelfId, String isbn);

  /** 해당 도서가 하나 이상의 서재에 연결되어 있는지 확인한다. */
  boolean existsShelfBookByUserBookId(UUID userBookId);

  /** 해당 서재에 도서가 이미 연결되어 있는지 확인한다. */
  boolean existsShelfBookByShelfIdAndUserBookId(UUID shelfId, UUID userBookId);

  /** 서재의 마지막 배치 위치를 조회한다. 연결된 도서가 없으면 빈 결과를 반환한다. */
  Optional<Integer> findMaxPositionByShelfId(UUID shelfId);

  /** 사용자 도서를 새로 생성한다. */
  UserBook createUserBook(UserBook userBook);

  /** 사용자 소유 도서의 독서 상태만 변경하고 메모와 메타데이터는 유지한다. */
  UserBook updateReadingStatus(UUID userBookId, UUID userId, ReadingStatus readingStatus);

  /** 사용자 소유 도서의 재등록 시 메모만 초기화한다. 독서 상태와 메타데이터는 유지한다. */
  UserBook resetForReregistration(UUID userBookId, UUID userId);

  /** 서재와 사용자 도서의 연결을 새로 생성한다. */
  ShelfBook createShelfBook(ShelfBook shelfBook);
}
