import io.github.fbricon.mdream4j.Clean;
import io.github.fbricon.mdream4j.Extraction;
import io.github.fbricon.mdream4j.Filter;
import io.github.fbricon.mdream4j.Frontmatter;
import io.github.fbricon.mdream4j.Options;
import io.github.fbricon.mdream4j.OutputFormat;
import io.github.fbricon.mdream4j.Plugins;
import io.github.fbricon.mdream4j.TagOverride;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and corpus-options mapping shared by the differential probes.
 *
 * <p>The mapping mirrors {@code mdream4j/src/test/java/io/github/fbricon/mdream4j/Corpus.java}
 * so probe cases use exactly the options the golden corpus recorded. Unknown option names fail
 * loudly instead of silently comparing against default-option output.
 */
final class Json {

  private final String source;
  private int index;

  private Json(String source) {
    this.source = source;
  }

  static Object parse(String source) {
    Json reader = new Json(source);
    reader.skipWhitespace();
    Object value = reader.readValue();
    reader.skipWhitespace();
    return value;
  }

  /** Builds {@link Options} from a case's compact options object and output format. */
  static Options options(String json, OutputFormat format) {
    Options.Builder builder = Options.builder().format(format);
    if (json == null || json.isEmpty()) {
      return builder.build();
    }
    Object parsed = parse(json);
    if (!(parsed instanceof Map)) {
      throw new IllegalArgumentException("options must be a JSON object: " + json);
    }
    Map<String, Object> options = asObject(parsed);
    for (Map.Entry<String, Object> entry : options.entrySet()) {
      String name = entry.getKey();
      Object value = entry.getValue();
      switch (name) {
        case "origin":
          builder.origin((String) value);
          break;
        case "wrapWidth":
          builder.wrapWidth(((Number) value).intValue());
          break;
        case "maxNodeBytes":
          builder.maxNodeBytes(((Number) value).longValue());
          break;
        case "clean":
          builder.clean(clean(asObject(value)));
          break;
        case "plugins":
          builder.plugins(plugins(asObject(value)));
          break;
        default:
          throw new IllegalArgumentException("unsupported corpus option: " + name);
      }
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asObject(Object value) {
    return (Map<String, Object>) value;
  }

  private static Clean clean(Map<String, Object> spec) {
    Clean.Builder clean = Clean.builder();
    for (Map.Entry<String, Object> entry : spec.entrySet()) {
      boolean enabled = (Boolean) entry.getValue();
      switch (entry.getKey()) {
        case "urls":
          clean.urls(enabled);
          break;
        case "fragments":
          clean.fragments(enabled);
          break;
        case "emptyLinks":
          clean.emptyLinks(enabled);
          break;
        case "redundantLinks":
          clean.redundantLinks(enabled);
          break;
        case "selfLinkHeadings":
          clean.selfLinkHeadings(enabled);
          break;
        case "emptyImages":
          clean.emptyImages(enabled);
          break;
        case "emptyLinkText":
          clean.emptyLinkText(enabled);
          break;
        default:
          throw new IllegalArgumentException("unsupported clean option: " + entry.getKey());
      }
    }
    return clean.build();
  }

  private static Plugins plugins(Map<String, Object> spec) {
    Plugins.Builder plugins = Plugins.builder();
    for (Map.Entry<String, Object> entry : spec.entrySet()) {
      switch (entry.getKey()) {
        case "filter":
          plugins.filter(filter(asObject(entry.getValue())));
          break;
        case "isolateMain":
          plugins.isolateMain((Boolean) entry.getValue());
          break;
        case "frontmatter":
          plugins.frontmatter(frontmatter(asObject(entry.getValue())));
          break;
        case "tailwind":
          plugins.tailwind((Boolean) entry.getValue());
          break;
        case "extraction":
          plugins.extraction(
              Extraction.of(strings(asObject(entry.getValue()).get("selectors"))));
          break;
        case "tagOverrides":
          for (Map.Entry<String, Object> override : asObject(entry.getValue()).entrySet()) {
            plugins.tagOverride(override.getKey(), tagOverride(asObject(override.getValue())));
          }
          break;
        default:
          throw new IllegalArgumentException("unsupported plugin option: " + entry.getKey());
      }
    }
    return plugins.build();
  }

  private static Filter filter(Map<String, Object> spec) {
    Filter.Builder filter = Filter.builder();
    for (Map.Entry<String, Object> entry : spec.entrySet()) {
      switch (entry.getKey()) {
        case "include":
          filter.include(strings(entry.getValue()));
          break;
        case "exclude":
          filter.exclude(strings(entry.getValue()));
          break;
        case "processChildren":
          filter.processChildren((Boolean) entry.getValue());
          break;
        default:
          throw new IllegalArgumentException("unsupported filter option: " + entry.getKey());
      }
    }
    return filter.build();
  }

  private static Frontmatter frontmatter(Map<String, Object> spec) {
    Frontmatter.Builder frontmatter = Frontmatter.builder();
    for (Map.Entry<String, Object> entry : spec.entrySet()) {
      switch (entry.getKey()) {
        case "additionalFields":
          for (Map.Entry<String, Object> field : asObject(entry.getValue()).entrySet()) {
            frontmatter.field(field.getKey(), (String) field.getValue());
          }
          break;
        case "metaFields":
          frontmatter.metaFields(strings(entry.getValue()));
          break;
        default:
          throw new IllegalArgumentException("unsupported frontmatter option: " + entry.getKey());
      }
    }
    return frontmatter.build();
  }

  private static TagOverride tagOverride(Map<String, Object> spec) {
    TagOverride.Builder override = TagOverride.builder();
    for (Map.Entry<String, Object> entry : spec.entrySet()) {
      Object value = entry.getValue();
      switch (entry.getKey()) {
        case "enter":
          override.enter((String) value);
          break;
        case "exit":
          override.exit((String) value);
          break;
        case "spacing":
          @SuppressWarnings("unchecked")
          List<Object> spacing = (List<Object>) value;
          override.spacing(
              ((Number) spacing.get(0)).intValue(), ((Number) spacing.get(1)).intValue());
          break;
        case "isInline":
          override.inline((Boolean) value);
          break;
        case "isSelfClosing":
          override.selfClosing((Boolean) value);
          break;
        case "collapsesInnerWhiteSpace":
          override.collapseInnerWhiteSpace((Boolean) value);
          break;
        case "alias":
          override.alias((String) value);
          break;
        default:
          throw new IllegalArgumentException("unsupported tag override option: " + entry.getKey());
      }
    }
    return override.build();
  }

  private static String[] strings(Object value) {
    @SuppressWarnings("unchecked")
    List<Object> list = (List<Object>) value;
    String[] strings = new String[list.size()];
    for (int i = 0; i < list.size(); i++) {
      strings[i] = (String) list.get(i);
    }
    return strings;
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
