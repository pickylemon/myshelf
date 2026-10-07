package com.myshelf.auth.application.port.out;

import java.util.UUID;

public interface AccessTokenIssuerPort {
  String issue(UUID userId);
}
