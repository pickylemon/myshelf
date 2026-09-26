package com.myshelf.user.adapter.out.persistence;

import com.myshelf.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserEntity {
  @Id private UUID id;

  @Column(unique = true, nullable = false)
  private String email;

  @Column(nullable = false)
  private String password;

  public static UserEntity from(User user) {
    return new UserEntity(user.getId(), user.getEmail(), user.getPassword());
  }

  public static User toDomain(UserEntity userEntity) {
    return new User(userEntity.getId(), userEntity.getEmail(), userEntity.getPassword());
  }
}
