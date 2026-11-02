/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.reference;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Test-only bridge to the upstream Rust mdream core.
 *
 * <p>This class uses {@code java.lang.foreign}, so it requires Java 25 and is
 * never part of the published library. When the native library is missing,
 * {@link #isAvailable()} returns {@code false} and tests should skip.
 */
@SuppressWarnings("restricted")
public final class ReferenceBridge {

  private static final boolean AVAILABLE;
  private static final Throwable LOAD_ERROR;

  private static final MethodHandle CONVERT;
  private static final MethodHandle FREE;
  private static final MethodHandle STREAM_NEW;
  private static final MethodHandle STREAM_FEED;
  private static final MethodHandle STREAM_FINISH;
  private static final MethodHandle STREAM_FREE;

  static {
    MethodHandle convert = null;
    MethodHandle free = null;
    MethodHandle streamNew = null;
    MethodHandle streamFeed = null;
    MethodHandle streamFinish = null;
    MethodHandle streamFree = null;
    Throwable error = null;
    boolean available = false;

    Path library = locateLibrary();
    if (library == null) {
      error = new IllegalStateException("native reference library not found; run: cargo build --release --manifest-path tools/reference/Cargo.toml");
    } else {
      try {
        System.load(library.toAbsolutePath().toString());
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.loaderLookup();

        convert = linker.downcallHandle(
            symbol(lookup, "mdream_convert"),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS));
        free = linker.downcallHandle(
            symbol(lookup, "mdream_free"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        streamNew = linker.downcallHandle(
            symbol(lookup, "mdream_stream_new"),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        streamFeed = linker.downcallHandle(
            symbol(lookup, "mdream_stream_feed"),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS));
        streamFinish = linker.downcallHandle(
            symbol(lookup, "mdream_stream_finish"),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        streamFree = linker.downcallHandle(
            symbol(lookup, "mdream_stream_free"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        available = true;
      } catch (Throwable t) {
        error = t;
      }
    }

    AVAILABLE = available;
    LOAD_ERROR = error;
    CONVERT = convert;
    FREE = free;
    STREAM_NEW = streamNew;
    STREAM_FEED = streamFeed;
    STREAM_FINISH = streamFinish;
    STREAM_FREE = streamFree;
  }

  private ReferenceBridge() {}

  /** Whether the native library is present and loaded. */
  public static boolean isAvailable() {
    return AVAILABLE;
  }

  /** The load failure, or {@code null} when available. */
  public static Throwable loadError() {
    return LOAD_ERROR;
  }

  /**
   * Converts HTML with the given JSON options.
   *
   * @param html UTF-8 HTML
   * @param optionsJson options in the shape accepted by the Rust bridge
   * @return the Markdown output
   */
  public static String convert(String html, String optionsJson) {
    Objects.requireNonNull(html, "html");
    Objects.requireNonNull(optionsJson, "optionsJson");
    ensureAvailable();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment htmlSegment = utf8(arena, html);
      MemorySegment optionsSegment = utf8(arena, optionsJson);
      MemorySegment outLength = arena.allocate(ValueLayout.JAVA_LONG);
      MemorySegment outTruncated = arena.allocate(ValueLayout.JAVA_BYTE);

      MemorySegment pointer = (MemorySegment) CONVERT.invokeExact(
          htmlSegment,
          htmlSegment.byteSize(),
          optionsSegment,
          optionsSegment.byteSize(),
          outLength,
          outTruncated);
      return readAndFree(pointer, outLength.get(ValueLayout.JAVA_LONG, 0));
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  /** Converts HTML with default options. */
  public static String convert(String html) {
    return convert(html, "{}");
  }

  /** Creates a streaming converter. */
  public static ReferenceStream newStream(String optionsJson) {
    Objects.requireNonNull(optionsJson, "optionsJson");
    ensureAvailable();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment optionsSegment = utf8(arena, optionsJson);
      MemorySegment handle =
          (MemorySegment) STREAM_NEW.invokeExact(optionsSegment, optionsSegment.byteSize());
      if (isNull(handle)) {
        throw new IllegalStateException("mdream_stream_new returned null");
      }
      return new ReferenceStream(handle);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  private static void ensureAvailable() {
    if (!AVAILABLE) {
      throw new IllegalStateException("reference bridge unavailable", LOAD_ERROR);
    }
  }

  private static MemorySegment utf8(Arena arena, String value) {
    CharsetEncoder encoder =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith(new byte[] {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD});
    try {
      ByteBuffer buffer = encoder.encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      return arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
    } catch (CharacterCodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static MemorySegment symbol(SymbolLookup lookup, String name) {    return lookup
        .find(name)
        .orElseThrow(() -> new IllegalStateException("missing native symbol: " + name));
  }

  private static boolean isNull(MemorySegment segment) {
    return segment == null || segment.address() == 0L;
  }

  private static String readAndFree(MemorySegment pointer, long length) throws Throwable {
    if (isNull(pointer)) {
      throw new IllegalStateException("reference conversion failed");
    }
    try {
      MemorySegment bytes = pointer.reinterpret(length);
      return new String(bytes.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    } finally {
      FREE.invokeExact(pointer, length);
    }
  }

  private static Path locateLibrary() {
    String configured = System.getProperty("mdream.reference.library");
    if (configured != null && !configured.isBlank()) {
      Path path = Path.of(configured);
      return Files.isRegularFile(path) ? path : null;
    }
    String mapped = System.mapLibraryName("mdream_reference");
    Path path = Path.of(System.getProperty("user.dir"))
        .getParent()
        .resolve("tools/reference/target/release")
        .resolve(mapped);
    return Files.isRegularFile(path) ? path : null;
  }

  /** Streaming conversion over the reference implementation. */
  public static final class ReferenceStream implements AutoCloseable {

    private MemorySegment handle;

    private ReferenceStream(MemorySegment handle) {
      this.handle = handle;
    }

    /** Feeds one chunk. */
    public String feed(String chunk) {
      Objects.requireNonNull(chunk, "chunk");
      Objects.requireNonNull(handle, "stream is closed");
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment chunkSegment = utf8(arena, chunk);
        MemorySegment outLength = arena.allocate(ValueLayout.JAVA_LONG);
        MemorySegment pointer = (MemorySegment) STREAM_FEED.invokeExact(
            handle, chunkSegment, chunkSegment.byteSize(), outLength);
        return readAndFree(pointer, outLength.get(ValueLayout.JAVA_LONG, 0));
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable t) {
        throw new IllegalStateException(t);
      }
    }

    /** Finishes the conversion and returns the remaining output. */
    public String finish() {
      Objects.requireNonNull(handle, "stream is closed");
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment outLength = arena.allocate(ValueLayout.JAVA_LONG);
        MemorySegment outTruncated = arena.allocate(ValueLayout.JAVA_BYTE);
        MemorySegment pointer = (MemorySegment) STREAM_FINISH.invokeExact(handle, outLength, outTruncated);
        return readAndFree(pointer, outLength.get(ValueLayout.JAVA_LONG, 0));
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable t) {
        throw new IllegalStateException(t);
      }
    }

    @Override
    public void close() {
      if (handle != null) {
        try {
          STREAM_FREE.invokeExact(handle);
        } catch (Throwable t) {
          throw new IllegalStateException(t);
        } finally {
          handle = null;
        }
      }
    }
  }
}
