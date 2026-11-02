/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import io.github.fbricon.mdream4j.reference.ReferenceBridge;
import java.nio.file.Files;
import java.nio.file.Path;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** The canonical implementation: upstream mdream through the FFM bridge. */
@State(Scope.Benchmark)
public class ReferenceBenchmark extends ConvertBenchmarkBase {

  @Override
  protected HtmlConverter createConverter() {
    if (System.getProperty("mdream.reference.library") == null) {
      Path library = findNativeLibrary();
      if (library != null) {
        System.setProperty("mdream.reference.library", library.toString());
      }
    }
    if (!ReferenceBridge.isAvailable()) {
      throw new IllegalStateException(
          "Rust reference library unavailable: " + ReferenceBridge.loadError());
    }
    return ReferenceBridge::convert;
  }

  /** Locates the cdylib from the repository root, however deep the fork starts. */
  private static Path findNativeLibrary() {
    String mapped = System.mapLibraryName("mdream_reference");
    Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int depth = 0; depth < 4 && directory != null; depth++) {
      Path candidate = directory.resolve("tools/reference/target/release").resolve(mapped);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    return null;
  }
}
