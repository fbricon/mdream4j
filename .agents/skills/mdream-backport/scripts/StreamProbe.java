import io.github.fbricon.mdream4j.HtmlToMarkdown;
import io.github.fbricon.mdream4j.MarkdownStream;
import io.github.fbricon.mdream4j.Options;
import io.github.fbricon.mdream4j.OutputFormat;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Streaming differential probe.
 *
 * <p>Reads the {@code MD4JSTR1} binary written by {@code to-stream-docs.py} and writes the same
 * {@code MD4JOUT1} result file read by {@code compare.py}. The output is every value returned by
 * {@link MarkdownStream#feed(CharSequence)} concatenated with {@link MarkdownStream#finish()},
 * which is what the Rust {@code stream-golden} binary reports as {@code streamed}.
 *
 * <pre>
 * MD4JSTR1
 * u32 case count
 * repeat:
 *   u32  id length,      id UTF-8
 *   u32  html length,    html UTF-8
 *   u32  options length, options JSON UTF-8
 *   u32  chunk size      in UTF-8 bytes, like stream-golden
 * </pre>
 *
 * <p>Compile and run from the repository root after {@code ./mvnw -pl mdream4j package}:
 *
 * <pre>
 * javac -cp mdream4j/target/classes:&lt;skill&gt;/scripts -d &lt;work&gt;/classes \
 *     &lt;skill&gt;/scripts/Json.java &lt;skill&gt;/scripts/StreamProbe.java
 * java -cp &lt;work&gt;/classes:mdream4j/target/classes StreamProbe cases.bin java.bin
 * </pre>
 */
public final class StreamProbe {

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: StreamProbe <cases.bin> <java.bin>");
      System.exit(2);
    }
    try (DataInputStream in =
            new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])));
        DataOutputStream out =
            new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])))) {
      byte[] magic = new byte[8];
      in.readFully(magic);
      String magicString = new String(magic, StandardCharsets.US_ASCII);
      if (!magicString.equals("MD4JSTR1")) {
        throw new IllegalArgumentException("bad case magic: " + magicString);
      }
      int count = in.readInt();
      out.write("MD4JOUT1".getBytes(StandardCharsets.US_ASCII));
      out.writeInt(count);
      for (int i = 0; i < count; i++) {
        String id = readString(in);
        String html = readString(in);
        String optionsJson = readString(in);
        int chunk = in.readInt();
        Options options = Json.options(optionsJson, OutputFormat.MARKDOWN);
        String output = "";
        boolean truncated = false;
        int status = 0;
        try {
          StringBuilder streamed = new StringBuilder();
          MarkdownStream stream = HtmlToMarkdown.newStream(options);
          byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
          int start = 0;
          while (start < bytes.length) {
            int end = Math.min(start + chunk, bytes.length);
            while (end < bytes.length && isContinuationByte(bytes[end])) {
              end++;
            }
            streamed.append(stream.feed(new String(bytes, start, end - start, StandardCharsets.UTF_8)));
            start = end;
          }
          streamed.append(stream.finish());
          output = streamed.toString();
          truncated = stream.truncated();
        } catch (RuntimeException | StackOverflowError error) {
          status = 1;
          output = error.getClass().getName() + ": " + error.getMessage();
        }
        writeString(out, id);
        out.writeByte(status);
        writeString(out, output);
        out.writeByte(truncated ? 1 : 0);
      }
    }
  }

  private static boolean isContinuationByte(byte value) {
    return (value & 0xC0) == 0x80;
  }

  private static String readString(DataInputStream in) throws Exception {
    int length = in.readInt();
    if (length < 0 || length > (1 << 30)) {
      throw new IllegalArgumentException("implausible string length: " + length);
    }
    byte[] bytes = new byte[length];
    in.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void writeString(DataOutputStream out, String value) throws Exception {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }
}
