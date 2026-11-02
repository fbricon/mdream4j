/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import io.github.fbricon.mdream4j.ExtractedElement;
import io.github.fbricon.mdream4j.Frontmatter;
import io.github.fbricon.mdream4j.MarkdownStream;
import io.github.fbricon.mdream4j.MdreamResult;
import io.github.fbricon.mdream4j.Options;
import io.github.fbricon.mdream4j.Plugins;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Internal conversion entry point.
 *
 * <p>Mirrors {@code html_to_format_result} in the upstream crate: one {@link ConvertState} feeds
 * the parser once, then finalizes its leftover input, then assembles the result.
 */
public final class Engine {

  /** Input bytes per reserved output byte for a one-shot conversion. */
  private static final int OUTPUT_RESERVE_DIVISOR = 3;

  private static final int MIN_ONE_SHOT_CAPACITY = 1024;
  private static final int MAX_ONE_SHOT_CAPACITY = 256 * 1024;

  private Engine() {}

  public static String convert(CharSequence html, Options options) {
    Objects.requireNonNull(html, "html");
    return convertResult(html, options).markdown();
  }

  public static MdreamResult convertResult(CharSequence html, Options options) {
    return convertResult(html, options, true);
  }

  /** One-shot conversion with the plain-text fast paths optionally disabled. */
  static MdreamResult convertResult(CharSequence html, Options options, boolean fastPaths) {
    Objects.requireNonNull(html, "html");
    Options resolved = options == null ? Options.defaults() : options;
    String source = html.toString();
    ConvertState state = new ConvertState(resolved, oneShotCapacity(source.length()));
    state.fastPaths = fastPaths;
    int consumed = ParseOps.processHtml(state, source);
    ParseOps.finalizeState(state, source.substring(consumed));
    String markdown = OutputOps.getMarkdown(state);
    return new MdreamResult(
        markdown, takeExtracted(state), frontmatter(state), state.truncated);
  }

  public static MarkdownStream newStream(Options options) {
    Options resolved = options == null ? Options.defaults() : options;
    return new ConverterStream(resolved);
  }

  /** Reserve low: output runs from a tenth of the input to over half. */
  private static int oneShotCapacity(int htmlLength) {
    int capacity = htmlLength / OUTPUT_RESERVE_DIVISOR;
    return Math.max(MIN_ONE_SHOT_CAPACITY, Math.min(capacity, MAX_ONE_SHOT_CAPACITY));
  }

  private static List<ExtractedElement> takeExtracted(ConvertState state) {
    if (!state.hasExtraction) {
      return null;
    }
    if (state.extractionResults.isEmpty()) {
      return null;
    }
    List<ExtractedElement> results = new ArrayList<>(state.extractionResults);
    state.extractionResults.clear();
    return results;
  }

  /** Assemble frontmatter entries (title, meta, plugin additional fields). */
  private static Map<String, String> frontmatter(ConvertState state) {
    if (!state.hasFrontmatter) {
      return null;
    }
    Map<String, String> entries = new LinkedHashMap<>();
    if (state.frontmatterTitle != null) {
      entries.put("title", state.frontmatterTitle);
    }
    for (String[] meta : state.frontmatterMeta) {
      entries.put(meta[0], meta[1]);
    }
    Plugins plugins = state.options.plugins();
    Frontmatter config = plugins == null ? null : plugins.frontmatter();
    if (config != null) {
      // The upstream config holds a BTreeMap, so extra fields come out key-sorted.
      List<Map.Entry<String, String>> fields =
          new ArrayList<>(config.additionalFields().entrySet());
      ParseOps.sortFieldsByKey(fields, Map.Entry::getKey);
      for (Map.Entry<String, String> field : fields) {
        String key = field.getKey();
        if (!"title".equals(key) && !"description".equals(key)) {
          entries.put(key, field.getValue());
        }
      }
    }
    return entries;
  }
}
