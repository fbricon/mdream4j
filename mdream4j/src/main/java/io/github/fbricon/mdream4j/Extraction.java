/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Extraction plugin: collect elements matching CSS selectors during
 * conversion.
 *
 * <p>Matched elements are returned by {@link MdreamResult#extracted()}.
 */
public final class Extraction {

  private final List<String> selectors;

  private Extraction(List<String> selectors) {
    this.selectors = Collections.unmodifiableList(new ArrayList<>(selectors));
  }

  /** Creates an extraction configuration for the given selectors. */
  public static Extraction of(String... selectors) {
    Objects.requireNonNull(selectors, "selectors");
    List<String> copy = new ArrayList<>(selectors.length);
    for (String selector : selectors) {
      copy.add(Objects.requireNonNull(selector, "selector"));
    }
    return new Extraction(copy);
  }

  /** Selectors whose matching elements are extracted. */
  public List<String> selectors() {
    return selectors;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof Extraction && selectors.equals(((Extraction) o).selectors));
  }

  @Override
  public int hashCode() {
    return selectors.hashCode();
  }

  @Override
  public String toString() {
    return "Extraction{selectors=" + selectors + '}';
  }
}
