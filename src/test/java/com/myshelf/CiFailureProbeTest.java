package com.myshelf;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

class CiFailureProbeTest {

  @Test
  void shouldFailToVerifyCi() {
    fail("Intentional CI failure");
  }
}
