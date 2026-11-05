package com.myshelf.book.application.port.out.registration;

import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.domain.ShelfBook;
import com.myshelf.book.domain.UserBook;
import java.util.Optional;
import java.util.UUID;

public interface BookRepositoryPort {

  /** 사용자와 ISBN으로 기존 도서를 조회한다. 서재 연결이 없는 도서도 포함한다. */
  Optional<UserBook> findUserBookByUserIdAndIsbn(UUID userId, String isbn);

  /** 해당 도서가 하나 이상의 서재에 연결되어 있는지 확인한다. */
  boolean existsShelfBookByUserBookId(UUID userBookId);

  /** 해당 서재에 도서가 이미 연결되어 있는지 확인한다. */
  boolean existsByShelfIdAndUserBookId(UUID shelfId, UUID userBookId);

  /** 사용자 도서를 새로 생성한다. */
  UserBook createUserBook(UserBook userBook);

  /** 사용자 소유 도서의 독서 상태만 변경하고 메모와 메타데이터는 유지한다. */
  UserBook updateReadingStatus(UUID userBookId, UUID userId, ReadingStatus readingStatus);

  /** 서재와 사용자 도서의 연결을 새로 생성한다. */
  ShelfBook createShelfBook(ShelfBook shelfBook);

  void updateShelfBookMemo(UUID shelfBookId, String memo);

  ShelfBook findShelfBookByIdAndUserId(UUID shelfBookId, UUID userId);

  void deleteShelfBook(UUID shelfBookId);

  UserBook findByUserBookIdAndUserId(UUID uuid, UUID uuid1);
}
