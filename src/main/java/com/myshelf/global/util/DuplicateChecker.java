package com.myshelf.global.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DuplicateChecker {
  public static <T> boolean isUnique(Collection<T> collection) {
    Set<T> seen = new HashSet<>();
    for (T elem : collection) {
      if (!seen.add(elem)) return false;
    }

    return true;
  }
}
