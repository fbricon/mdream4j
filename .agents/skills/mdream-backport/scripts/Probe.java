import io.github.fbricon.mdream4j.HtmlToMarkdown;
import io.github.fbricon.mdream4j.MdreamResult;
import io.github.fbricon.mdream4j.Options;
import io.github.fbricon.mdream4j.OutputFormat;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One-shot differential probe.
 *
 * <p>Reads the {@code MD4JDOC1} binary written by {@code to-docs.py} and writes the
 * {@code MD4JOUT1} result file read by {@code compare.py}:
 *
 * <pre>
 * MD4JOUT1
 * u32 case count
 * repeat:
 *   u32  id length,      id UTF-8
 *   u8   status          0 = ok, 1 = error (the message is the output)
 *   u32  output length,  output UTF-8
 *   u8   truncated flag
 * </pre>
 *
 * <p>Compile and run from the repository root after {@code ./mvnw -pl mdream4j package}:
 *
 * <pre>
 * javac -cp mdream4j/target/classes:&lt;skill&gt;/scripts -d &lt;work&gt;/classes \
 *     &lt;skill&gt;/scripts/Json.java &lt;skill&gt;/scripts/Probe.java
 * java -cp &lt;work&gt;/classes:mdream4j/target/classes Probe cases.bin java.bin
 * </pre>
 */
public final class Probe {

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: Probe <cases.bin> <java.bin>");
      System.exit(2);
    }
    try (DataInputStream in =
            new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])));
        DataOutputStream out =
            new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])))) {
      byte[] magic = new byte[8];
      in.readFully(magic);
      if (!new String(magic, StandardCharsets.US_ASCII).equals("MD4JDOC1")) {
        throw new IllegalArgumentException("bad case magic: " + new String(magic, StandardCharsets.US_ASCII));
      }
      int count = in.readInt();
      out.write("MD4JOUT1".getBytes(StandardCharsets.US_ASCII));
      out.writeInt(count);
      for (int i = 0; i < count; i++) {
        String id = readString(in);
        String html = readString(in);
        String optionsJson = readString(in);
        Options options = Json.options(optionsJson, OutputFormat.MARKDOWN);
        String output = "";
        boolean truncated = false;
        int status = 0;
        try {
          MdreamResult result = HtmlToMarkdown.convertResult(html, options);
          output = result.markdown();
          truncated = result.truncated();
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
