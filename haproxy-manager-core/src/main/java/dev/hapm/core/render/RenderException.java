package dev.hapm.core.render;

import java.util.List;

/** Problemas que só aparecem olhando o cluster inteiro (referências quebradas, portas em conflito...). */
public class RenderException extends RuntimeException {
  private final List<String> errors;

  public RenderException(List<String> errors) {
    super(String.join("; ", errors));
    this.errors = List.copyOf(errors);
  }

  public List<String> getErrors() {
    return errors;
  }
}
