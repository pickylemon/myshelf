# 헥사고날 아키텍처에서 기능 영역 간 협력하기

## 1. 문제 상황

MyShelf의 `BookService`는 Shelf에 도서를 추가하기 전에 **해당 Shelf가 존재하고 인증 사용자 소유인지** 확인해야 한다.

```text
BookService
  ├─ implements ShelfBookAddUseCase  // Book이 제공하는 기능
  └─ uses ShelfGetUseCase            // Book이 필요로 하는 Shelf 기능
       └─ Shelf 쪽 구현체
            └─ ShelfRepositoryPort → Persistence Adapter
```

`shelf.application.port.in.ShelfGetUseCase#get(shelfId, userId)`는 Shelf가 없거나 타인 소유이면 동일한 예외를 발생시키는 정책을 이미 제공한다.

## 2. `implements`와 주입의 차이

| 구분 | 의미 | MyShelf 예시 |
|---|---|---|
| `implements` | 내가 제공할 기능의 계약을 구현 | `BookService implements ShelfBookAddUseCase` |
| 생성자 주입 | 내가 수행할 작업에 필요한 협력 기능을 사용 | `BookService(ShelfGetUseCase shelfGetUseCase)` |

```java
// 메서드명 addBooks는 설명을 위한 예시
public class BookService implements ShelfBookAddUseCase {
    private final ShelfGetUseCase shelfGetUseCase;

    public BookService(ShelfGetUseCase shelfGetUseCase) {
        this.shelfGetUseCase = shelfGetUseCase;
    }

    @Override
    public void addBooks(ShelfBookAddCommand command) {
        shelfGetUseCase.get(command.shelfId(), command.userId());
        // Shelf 검증에 성공한 뒤 도서 등록 처리
    }
}
```

`implements`와 주입은 서로 반대되는 선택지가 아니다. **하나의 Service가 자기 UseCase를 구현하면서, 다른 UseCase를 주입받아 사용할 수 있다.**

## 3. Input Port와 Output Port의 구분

- **Input Port:** 애플리케이션이 외부 호출자에게 제공하는 기능(UseCase)의 계약. 예: `ShelfBookAddUseCase`, `ShelfGetUseCase`.
- **Output Port:** 애플리케이션이 기능을 수행하기 위해 외부 구현에 요청하는 능력의 계약. 예: `ShelfRepositoryPort`.

따라서 구분 기준은 단순히 `SELECT`/`INSERT` 여부가 아니라 **누가 어떤 책임을 제공하고, 누가 무엇을 필요로 하는가**이다. Output Port가 항상 DB 전용인 것도 아니다.

## 4. Shelf 소유권 확인: 세 가지 선택지

| 방식 | 장점 | 주의할 점 | 현재 판단 |
|---|---|---|---|
| ① `ShelfGetUseCase` 주입 | 기존 소유권·예외 정책 재사용 | Shelf Application API에 의존 | **우선 선택** |
| ② `ShelfRepositoryPort` 직접 주입 | 조회 조건을 직접 제어 | 저장소 계약에 결합, 정책 중복 가능 | 보류 |
| ③ Book 전용 Output Port 정의 | 소비자 관점의 독립적 계약 | Port 및 Adapter 추가 | 필요 시 검토 |

현재는 Shelf의 기존 UseCase가 **필요한 검증 의미와 실패 정책을 정확히 제공**하므로 ①이 단순하다.

② `ShelfRepositoryPort`를 직접 사용하는 것도 헥사고날 아키텍처 위반은 아니다.

다만 이 경우 BookService가 Shelf 조회 결과를 직접 판단하고, 소유권 검증과 예외 처리까지 구현해야 한다.

반면 `ShelfGetUseCase`를 사용하면 Shelf 쪽에 이미 구현된 검증 정책을 그대로 재사용할 수 있다.

따라서 **기존 정책을 재사용할 수 있는지, Book에서 별도의 판단이 필요한지**를 기준으로 의존 대상을 선택한다.

③은 Book이 Shelf의 공개 API와 다른 의미의 조회를 요구하거나, 기능별 독립 배포·모듈 분리·구현 교체를 염두에 두어야 할 때 유용하다. 단지 다른 패키지의 UseCase를 호출한다는 이유만으로 만들 필요는 없다.

## 5. 기능 간 협력과 Domain 객체 의존은 다르다

- **기능 간 협력:** `BookService`가 `ShelfGetUseCase.get()`을 호출해 Shelf의 검증 정책을 활용한다.
- **Domain 객체 의존:** Book 코드가 `Shelf`의 필드·메서드·도메인 규칙에 직접 의존한다.

`get()`이 `Shelf`를 반환하더라도 Book에서 소유권 검증만 필요하면 반환 객체를 사용하지 않아도 된다. 추후 불필요한 도메인 결합이 커진다면 검증 전용 계약을 검토할 수 있지만, **지금 별도 Port를 추가할 필요는 없다.**

또한 `shelf/`, `book/`처럼 패키지가 나뉘었다는 이유만으로 각 영역을 독립된 Bounded Context 또는 별도 헥사곤으로 단정하지 않는다.

## 6. 순환 의존과 트랜잭션

- **순환 의존:** `Book → Shelf` 의존이 있다면 `Shelf → Book`도 추가되면서 순환하지 않는지 확인한다. 순환이 생길 경우 호출 방향과 책임 배치를 재검토한다.
- **트랜잭션:** 도서 등록이 하나의 DB 트랜잭션이어야 한다면 등록 UseCase에 트랜잭션 경계를 둔다. Shelf 조회 UseCase가 기본 전파 방식(`REQUIRED`)이고 같은 트랜잭션 관리자를 사용한다면 기존 트랜잭션에 참여할 수 있다.
- **TOCTOU:** `findByIdAndUserId`로 소유권을 확인하는 것은 인가 검증이다. 검증 직후 다른 트랜잭션이 Shelf를 삭제하는 경쟁 상황까지 막는 것은 아니다. 외래 키 등 DB 제약으로 무결성을 보호하고, 필요할 때만 별도 동시성 전략을 고려한다.

## 7. 의존 대상 선택 기준

1. **다른 기능에 이미 필요한 UseCase가 있는가?**
    - 있다면 해당 Input Port를 주입받아 재사용하는 것을 우선 검토한다.

2. **기존 UseCase가 제공하지 않는 데이터 조회가 필요한가?**
    - RepositoryPort 직접 참조 또는 기존 기능의 UseCase 확장을 검토한다.
    - 단, 다른 기능의 비즈니스 규칙을 중복 구현하지 않도록 주의한다.

3. **다른 기능의 내부 구조에 대한 의존을 줄여야 하는가?**
    - 소비하는 기능에 별도의 Output Port를 정의하고 Adapter에서 연결하는 방식을 검토한다.

4. **새로운 Port가 실제로 필요한가?**
    - 단순히 기존 UseCase를 한 번 더 호출하는 역할이라면 불필요한 추상화일 수 있다.

5. **기능 간 호출로 다른 문제가 발생하지 않는가?**
    - 순환 의존 여부와 트랜잭션 경계를 확인한다.
    - 소유권 검증과 동시성 문제 해결은 별도로 판단한다.

**결론:** `내가 제공할 기능은 implements`, `내가 필요한 협력 기능은 주입`이 출발점이다. 포트 선택은 패키지 규칙을 기계적으로 따르기보다 **정책 재사용, 결합도, 변경 가능성**으로 판단한다.