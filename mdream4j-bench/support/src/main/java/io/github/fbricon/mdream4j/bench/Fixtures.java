/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The benchmark corpus.
 *
 * <p>The real documents are copied from the pinned {@code tools/upstream} submodule at build time.
 * Synthetic inputs are generated once, so every contender sees identical bytes.
 */
public final class Fixtures {

  /** Real documents: benchmark name to the upstream fixture file. */
  private static final Map<String, String> RESOURCES = new LinkedHashMap<>();

  private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

  static {
    RESOURCES.put("nuxt", "nuxt-example.html");
    RESOURCES.put("vuejs-docs", "vuejs-docs.html");
    RESOURCES.put("wikipedia", "wikipedia-small.html");
    RESOURCES.put("mdn", "mdn-array.html");
    RESOURCES.put("react", "react-learn.html");
    RESOURCES.put("github", "github-markdown-complete.html");
    RESOURCES.put("wikipedia-xl", "wikipedia-largest.html");
  }

  private Fixtures() {}

  /**
   * Loads a fixture by its benchmark name.
   *
   * @param name a real or synthetic fixture name
   * @return the HTML document
   */
  public static String load(String name) {
    return CACHE.computeIfAbsent(name, Fixtures::read);
  }

  private static String read(String name) {
    String resource = RESOURCES.get(name);
    if (resource == null) {
      return synthetic(name);
    }
    String path = "/fixtures/" + resource;
    try (InputStream in = Fixtures.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing benchmark fixture " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read benchmark fixture " + path, e);
    }
  }

  private static String synthetic(String name) {
    switch (name) {
      case "depth-512":
        return repeat("<div>", 512) + "<p>deep</p>" + repeat("</div>", 512);
      case "table-huge":
        return hugeTable();
      case "node-1mb":
        return "<p>" + repeat("x", 1024 * 1024) + "</p>";
      case "text-10mb":
        return "<p>" + fill("Ordinary words in a small paragraph. ", 10 * 1024 * 1024) + "</p>";
      default:
        throw new IllegalArgumentException("unknown benchmark fixture: " + name);
    }
  }

  private static String hugeTable() {
    StringBuilder html = new StringBuilder(2 * 1024 * 1024);
    html.append("<table><thead><tr>");
    for (int column = 0; column < 8; column++) {
      html.append("<th>Column ").append(column).append("</th>");
    }
    html.append("</tr></thead><tbody>");
    for (int row = 0; row < 2000; row++) {
      html.append("<tr>");
      for (int column = 0; column < 8; column++) {
        html.append("<td>cell ").append(row).append('.').append(column).append("</td>");
      }
      html.append("</tr>");
    }
    return html.append("</tbody></table>").toString();
  }

  private static String repeat(String value, int count) {
    StringBuilder out = new StringBuilder(value.length() * count);
    for (int i = 0; i < count; i++) {
      out.append(value);
    }
    return out.toString();
  }

  private static String fill(String unit, int targetLength) {
    StringBuilder out = new StringBuilder(targetLength + unit.length());
    while (out.length() < targetLength) {
      out.append(unit);
    }
    return out.toString();
  }
}
