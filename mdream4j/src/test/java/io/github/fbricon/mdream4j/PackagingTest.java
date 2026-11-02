/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the published artifact's contract: Java 11 bytecode, a JPMS descriptor that exports only
 * the public package, an OSGi manifest with the same boundary, and no native-image configuration.
 *
 * <p>Reads the build output rather than the packaged jar so it runs in the normal {@code test}
 * phase.
 */
class PackagingTest {

  private static final Path CLASSES = Path.of("target", "classes");
  private static final Path MANIFEST = CLASSES.resolve("META-INF").resolve("MANIFEST.MF");

  @Test
  void moduleDescriptorExportsOnlyThePublicApi() {
    ModuleDescriptor descriptor =
        ModuleFinder.of(CLASSES).find("io.github.fbricon.mdream4j").orElseThrow().descriptor();

    assertEquals(
        Set.of("io.github.fbricon.mdream4j"),
        descriptor.exports().stream()
            .map(ModuleDescriptor.Exports::source)
            .collect(Collectors.toSet()));
    assertTrue(
        descriptor.exports().stream().allMatch(exports -> exports.targets().isEmpty()),
        "the module must export unconditionally");
    assertEquals(
        List.of("java.base"),
        descriptor.requires().stream()
            .map(ModuleDescriptor.Requires::name)
            .collect(Collectors.toList()));
    assertTrue(descriptor.uses().isEmpty(), "no ServiceLoader use");
    assertTrue(descriptor.provides().isEmpty(), "no ServiceLoader provider");
  }

  @Test
  void osgiManifestMatchesTheModuleBoundary() throws IOException {
    Attributes attributes = manifest();

    assertEquals("io.github.fbricon.mdream4j", string(attributes, "Bundle-SymbolicName"));
    assertEquals("JavaSE-11", string(attributes, "Bundle-RequiredExecutionEnvironment"));
    assertNotNull(attributes.getValue("Bundle-Version"));
    assertTrue(
        string(attributes, "Export-Package").startsWith("io.github.fbricon.mdream4j;version="),
        "only the public package is exported");
    assertFalse(
        string(attributes, "Export-Package").contains(".internal"),
        "the internal package must stay private");
    assertTrue(
        string(attributes, "Private-Package").contains("io.github.fbricon.mdream4j.internal"),
        "the internal package is included but not exported");
    assertEquals(
        null,
        attributes.getValue("Import-Package"),
        "the bundle has no runtime dependencies, so it imports no packages");
  }

  @Test
  void bytecodeTargetsJava11() throws IOException {
    Path classFile = CLASSES.resolve("io/github/fbricon/mdream4j/HtmlToMarkdown.class");
    byte[] header = Files.readAllBytes(classFile);
    int major = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
    assertEquals(55, major, "class files must stay at the Java 11 class file version");
  }

  @Test
  void shipsNoNativeImageConfiguration() {
    assertFalse(
        Files.exists(CLASSES.resolve("META-INF").resolve("native-image")),
        "the library must stay zero-config for native-image");
  }

  private static Attributes manifest() throws IOException {
    try (InputStream in = Files.newInputStream(MANIFEST)) {
      return new Manifest(in).getMainAttributes();
    }
  }

  private static String string(Attributes attributes, String name) {
    String value = attributes.getValue(name);
    assertNotNull(value, name + " is missing from the OSGi manifest");
    return value;
  }
}
