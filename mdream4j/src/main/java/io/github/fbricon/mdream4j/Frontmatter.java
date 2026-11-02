/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Frontmatter plugin: emit YAML frontmatter extracted from {@code <head>}
 * metadata.
 */
public final class Frontmatter {

  private final Map<String, String> additionalFields;
  private final List<String> metaFields;

  private Frontmatter(Builder builder) {
    this.additionalFields =
        Collections.unmodifiableMap(new LinkedHashMap<>(builder.additionalFields));
    this.metaFields = Collections.unmodifiableList(new ArrayList<>(builder.metaFields));
  }

  /** Enables the plugin with default metadata extraction. */
  public static Frontmatter defaults() {
    return builder().build();
  }

  /** Enables the plugin and extracts the given extra {@code <meta>} names. */
  public static Frontmatter withMetaFields(String... fields) {
    return builder().metaFields(fields).build();
  }

  /** Returns a builder for a frontmatter configuration. */
  public static Builder builder() {
    return new Builder();
  }

  /** Extra key/value pairs appended to the frontmatter. */
  public Map<String, String> additionalFields() {
    return additionalFields;
  }

  /** Additional meta names to extract beyond the defaults. */
  public List<String> metaFields() {
    return metaFields;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Frontmatter)) {
      return false;
    }
    Frontmatter other = (Frontmatter) o;
    return additionalFields.equals(other.additionalFields) && metaFields.equals(other.metaFields);
  }

  @Override
  public int hashCode() {
    return Objects.hash(additionalFields, metaFields);
  }

  @Override
  public String toString() {
    return "Frontmatter{additionalFields=" + additionalFields
        + ", metaFields=" + metaFields + '}';
  }

  /** Builder for {@link Frontmatter}. */
  public static final class Builder {

    private final Map<String, String> additionalFields = new LinkedHashMap<>();
    private final List<String> metaFields = new ArrayList<>();

    private Builder() {}

    /** Adds an extra frontmatter field. */
    public Builder field(String name, String value) {
      additionalFields.put(
          Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
      return this;
    }

    /** Adds extra {@code <meta>} names to extract. */
    public Builder metaFields(String... fields) {
      Objects.requireNonNull(fields, "fields");
      for (String field : fields) {
        metaFields.add(Objects.requireNonNull(field, "field"));
      }
      return this;
    }

    /** Builds an immutable {@link Frontmatter}. */
    public Frontmatter build() {
      return new Frontmatter(this);
    }
  }
}
