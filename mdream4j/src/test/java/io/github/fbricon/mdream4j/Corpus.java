/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads {@code golden/corpus.jsonl} for the differential tests. */
final class Corpus {

  private Corpus() {}

  static List<Map<String, Object>> loadCases() throws IOException {
    Path corpus = findCorpus();
    List<Map<String, Object>> cases = new ArrayList<>();
    for (String line : Files.readAllLines(corpus, StandardCharsets.UTF_8)) {
      if (line.isEmpty()) {
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> entry = (Map<String, Object>) new Json(line).parse();
      cases.add(entry);
    }
    return cases;
  }

  static Path findCorpus() {
    Path here = Paths.get("").toAbsolutePath();
    for (Path dir = here; dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve("golden").resolve("corpus.jsonl");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("golden/corpus.jsonl not found above " + here);
  }

  /** Minimal JSON reader for the corpus shape. */
  private static final class Json {
    private final String source;
    private int index;

    Json(String source) {
      this.source = source;
    }

    Object parse() {
      skipWhitespace();
      Object value = readValue();
      skipWhitespace();
      return value;
    }

    private Object readValue() {
      char c = source.charAt(index);
      switch (c) {
        case '{':
          return readObject();
        case '[':
          return readArray();
        case '"':
          return readString();
        case 't':
          expect("true");
          return Boolean.TRUE;
        case 'f':
          expect("false");
          return Boolean.FALSE;
        case 'n':
          expect("null");
          return null;
        default:
          return readNumber();
      }
    }

    private Map<String, Object> readObject() {
      Map<String, Object> object = new LinkedHashMap<>();
      index++;
      skipWhitespace();
      if (source.charAt(index) == '}') {
        index++;
        return object;
      }
      while (true) {
        skipWhitespace();
        String key = readString();
        skipWhitespace();
        index++; // ':'
        skipWhitespace();
        object.put(key, readValue());
        skipWhitespace();
        char c = source.charAt(index++);
        if (c == '}') {
          return object;
        }
      }
    }

    private List<Object> readArray() {
      List<Object> array = new ArrayList<>();
      index++;
      skipWhitespace();
      if (source.charAt(index) == ']') {
        index++;
        return array;
      }
      while (true) {
        skipWhitespace();
        array.add(readValue());
        skipWhitespace();
        char c = source.charAt(index++);
        if (c == ']') {
          return array;
        }
      }
    }

    private String readString() {
      StringBuilder out = new StringBuilder();
      index++;
      while (true) {
        char c = source.charAt(index++);
        if (c == '"') {
          return out.toString();
        }
        if (c != '\\') {
          out.append(c);
          continue;
        }
        char escape = source.charAt(index++);
        switch (escape) {
          case 'b':
            out.append('\b');
            break;
          case 'f':
            out.append('\f');
            break;
          case 'n':
            out.append('\n');
            break;
          case 'r':
            out.append('\r');
            break;
          case 't':
            out.append('\t');
            break;
          case 'u':
            out.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
            index += 4;
            break;
          default:
            out.append(escape);
        }
      }
    }

    private Object readNumber() {
      int start = index;
      while (index < source.length()) {
        char c = source.charAt(index);
        if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
          break;
        }
        index++;
      }
      return Long.parseLong(source.substring(start, index));
    }

    private void expect(String literal) {
      if (!source.startsWith(literal, index)) {
        throw new IllegalStateException("expected " + literal + " at " + index);
      }
      index += literal.length();
    }

    private void skipWhitespace() {
      while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
        index++;
      }
    }
  }
}
