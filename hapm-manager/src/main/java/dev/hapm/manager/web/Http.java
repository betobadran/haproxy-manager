package dev.hapm.manager.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class Http {
  private Http() {}

  static ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what);
  }

  static ResponseStatusException status(HttpStatus s, String msg) {
    return new ResponseStatusException(s, msg);
  }
}
