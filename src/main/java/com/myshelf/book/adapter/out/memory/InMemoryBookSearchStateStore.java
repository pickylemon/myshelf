package com.myshelf.book.adapter.out.memory;

import com.myshelf.book.application.model.BookSearchState;
import com.myshelf.book.application.port.out.search.BookSearchStatePort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// TODO: 검색 세션 TTL 적용 (사용자 이탈 시 메모리 누수 방지)
// TODO: 동일 searchId의 동시 요청에 대한 상태 갱신 원자성 보장
@Component
@Profile("!redis")
public class InMemoryBookSearchStateStore implements BookSearchStatePort {
  private final Map<UUID, BookSearchState> stateBySearchId = new ConcurrentHashMap<>();

  @Override
  public void save(UUID searchId, BookSearchState state) {
    stateBySearchId.put(searchId, state);
  }

  @Override
  public Optional<BookSearchState> findById(UUID searchId) {
    return Optional.ofNullable(stateBySearchId.get(searchId));
  }

  @Override
  public void delete(UUID searchId) {
    stateBySearchId.remove(searchId);
  }
}
