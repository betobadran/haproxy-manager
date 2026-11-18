package dev.hapm.manager.web;

import dev.hapm.core.render.RenderException;
import dev.hapm.core.validate.ValidationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(ValidationException.class)
  ResponseEntity<Map<String, Object>> validation(ValidationException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "errors", e.getErrors()));
  }

  @ExceptionHandler(RenderException.class)
  ResponseEntity<Map<String, Object>> render(RenderException e) {
    return ResponseEntity.unprocessableContent().body(Map.of("error", e.getMessage(), "errors", e.getErrors()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "não encontrado"));
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> badJson(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("error", "JSON inválido: " + e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> other(Exception e) {
    log.error("erro interno", e);
    return ResponseEntity.internalServerError().body(Map.of("error", "erro interno"));
  }
}
