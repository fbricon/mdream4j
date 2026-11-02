/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

/**
 * SHA-256 checks against the reference output.
 *
 * <p>{@code bench-digests.properties} is generated from the Rust reference by {@code
 * tools/bench/digests.mjs}. Only mdream4j asserts against it: the other contenders legitimately
 * produce different Markdown. Every contender still verifies that its own output is stable across
 * the trial.
 */
public final class Digests {

  private static final Properties GOLDEN = load();

  private Digests() {}

  /** SHA-256 of the UTF-8 encoding of {@code value}, as lowercase hex. */
  public static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }

  /**
   * Fails when the output differs from the checked-in reference digest.
   *
   * @param fixture benchmark fixture name
   * @param output the converter output
   */
  public static void assertGolden(String fixture, String output) {
    String expected = GOLDEN.getProperty(fixture + ".sha256");
    if (expected == null) {
      return;
    }
    String actual = sha256(output);
    if (!expected.equals(actual)) {
      throw new AssertionError(
          fixture
              + ": output diverged from the reference (expected "
              + expected
              + ", got "
              + actual
              + ", length "
              + output.length()
              + ")");
    }
  }

  private static Properties load() {
    Properties properties = new Properties();
    try (InputStream in = Digests.class.getResourceAsStream("/bench-digests.properties")) {
      if (in != null) {
        properties.load(in);
      }
      return properties;
    } catch (IOException e) {
      throw new IllegalStateException("cannot read bench-digests.properties", e);
    }
  }
}
