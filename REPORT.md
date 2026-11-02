# mdream4j — Pure-Java port vs. FFM binding to the Rust engine

Research report, 2026-11-02. All local measurements were taken on this machine:
macOS 27.0.1 / Apple Silicon (arm64), Temurin JDK 25.0.4.1, Rust 1.99.1, mdream 2.0.3
(`crates/core`, release profile with `lto = true`, `strip = true`, `codegen-units = 1`).
Benchmarks are best-of-N wall-clock loops, not JMH; treat them as order-of-magnitude
evidence, not certification. The FFM and Rust code in §2–§3 was compiled and executed;
outputs were diffed against the upstream Rust results.

> **Addendum (same day, after reviewing `io.xberg:html-to-markdown`): the verdict in §0 is
> superseded.** That project already ships this exact design — Java 25 Panama + a bundled Rust
> cdylib, 6 native libraries, 15 MB JAR, MIT, 888★, actively maintained — and its Java layer
> depends on Jackson and serialises options/results through JSON on every call. So FFM is not an
> open niche, and its "faster to ship" advantage is smaller than assumed. The recommendation is now
> **port the core to pure Java 25, and keep FFM only as a test-only reference bridge and benchmark baseline**.
> See [`PLAN.md`](./PLAN.md) §2 and Appendix A for the revised decision matrix.

---

## 0. Verdict

**Build option B (FFM binding to the existing Rust engine), and do not port the engine.**

The decisive argument is not speed — it is that mdream already carries **two**
implementations (Rust + a 14,312-LOC TypeScript engine) and pays a visible tax to keep
them byte-identical (`AGENTS.md`: "The two engines must produce the same output. Cross-engine
parity tests live in `packages/mdream/test/unit/`"). Upstream moved **293 commits in the last
90 days** (967 total; HEAD 2026-10-31). A Java port would be a third implementation to
re-sync every quarter, against a 20,003-LOC core with 903 tests and two fuzz targets.

Option A only wins if "one JAR, zero native artifacts, runs on Android" is a hard product
requirement. Even then, Android cannot use FFM at all (no `java.lang.foreign` in the Android
API — the official API reference 404s), so the "native" option isn't available there anyway;
the comparison on Android is port vs. JNI, and JNI is strictly worse than FFM.

Measured on this machine: a full FFM conversion of a 166 KB page costs **337 µs**, of which
the FFI boundary is **≤ 0.8 µs (≈0.2 %)**; a bare downcall is 3.6 ns. The native route is
essentially free at the call site.

---

## 1. What is actually being ported

| Fact | Value | Source |
|---|---|---|
| Core size | 20,003 LOC Rust (`crates/core/src`) | `wc -l` |
| Dependencies | **zero** (`cargo tree -p mdream` → only `mdream`) | local |
| `unsafe` | workspace lint `unsafe_code = "deny"` | `crates/Cargo.toml:18` |
| Tests | 903 `#[test]` fns; 22 integration test files; 8 fixture pages (1.2 MB) | local |
| Fuzzing | `cargo-fuzz`, targets `fuzz_html_to_markdown`, `fuzz_streaming` | `crates/core/fuzz` |
| Second engine | 14,312 LOC TypeScript (`packages/js/src`) | local |
| Native targets already shipped (NAPI) | 13, incl. `x86_64/aarch64-unknown-linux-musl`, `aarch64-linux-android`, `x86_64-unknown-freebsd`, `aarch64-pc-windows-msvc` | `crates/node/package.json` |
| Release profile | `panic = "unwind"` (comment: "napi catches unwinds and turns them into JS exceptions") | `crates/Cargo.toml` |
| Licence | MIT | `LICENSE.md` |
| Velocity | 293 commits / 90 days, 967 total | `git log` |

Relevant detail for FFI: the core has **no global mutable state** in release builds (the only
`thread_local!` is `#[cfg(test)]` in `url.rs`), and every conversion owns its `ConvertState`.
That makes it trivially safe to call concurrently from multiple Java threads, one handle per call.

Existing bindings are *not* reusable as a C ABI:

| Crate | Mechanism | Reusable for C ABI? |
|---|---|---|
| `crates/node` | NAPI (`napi` 3.x, `cdylib`, `#[napi]` structs) | No — NAPI is a JNI-like VM ABI. But its option-mapping code and `catch_panic` wrapper are a template. |
| `crates/edge` | `wasm-bindgen`, `cdylib`, `JsValue` parsing | No. But `process_chunk_bytes` already implements the hard part of a byte-streaming ABI: UTF-8 carry across chunk boundaries (see §3.4). |
| `crates/core` | plain Rust `&str -> String` | Yes — this is the thing to wrap, ~200 LOC of shim. |

---

## 2. Option B mechanics: FFM in Java 25

### 2.1 The object model

| Concept | Type | Lifetime / rule |
|---|---|---|
| ABI access | `Linker.nativeLinker()` | platform C ABI; supports Linux x64/aarch64/RISC-V/ppc64/s390, macOS x64/aarch64, Windows x64/aarch64, AIX (JEP 454) |
| Symbol resolution | `SymbolLookup.libraryLookup(Path, Arena)` | loads via `dlopen`; **unloads (`dlclose`) when the arena closes** — use `Arena.global()` in a library |
| Function pointer | `MemorySegment` (zero-length) | returned pointers have size 0 until `reinterpret(len)` |
| Callable | `MethodHandle` from `Linker.downcallHandle` | cache once in a `static final`; thread-safe, JIT-inlined |
| Memory | `Arena.ofConfined()` | one thread, deterministic free at `close()` |
| | `Arena.ofShared()` | multi-thread, `close()` may block to cancel in-flight access |
| | `Arena.ofAuto()` | GC-driven, non-deterministic |
| | `Arena.global()` | never freed, always alive |
| Callback | `Linker.upcallStub(MethodHandle, FunctionDescriptor, Arena)` | returns a `MemorySegment` usable as a C function pointer; stub dies with its arena |

Two hard rules from the JDK 25 javadoc that shape the design:

* **Upcalls must not throw.** "The target method handle should not throw any exceptions. If the
  target method handle does throw an exception, the JVM will terminate abruptly." Wrap the body
  in `try/catch (Throwable)`.
* **Heap segments are rejected** by a normal downcall; only `Linker.Option.critical(true)` allows
  them, and `critical` is documented for functions with "extremely short running time … and
  [that do] not call back into Java". A 5 ms HTML conversion is *not* critical; do not use it.

Threading: a downcall handle is shareable; a `MemorySegment` from `ofConfined()` is not
(cross-thread use throws `WrongThreadException`). So: one handle cached globally, one confined
arena per conversion (or per worker thread, reused).

### 2.2 Minimal, working one-shot binding (compiled and run)

Rust side (`crates/capi/src/lib.rs`):

```rust
use std::os::raw::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Returns an owned buffer; caller must pass (ptr, len) to mdream_string_free.
/// out_err: 0 ok, 1 null, 2 invalid UTF-8, 3 panic.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mdream_convert(
    html: *const c_char, html_len: usize,
    out_len: *mut usize, out_err: *mut u32,
) -> *mut c_char {
    unsafe { *out_len = 0; *out_err = 0 };
    if out_len.is_null() { return std::ptr::null_mut(); }
    if html.is_null() && html_len != 0 { unsafe { *out_err = 1 }; return std::ptr::null_mut(); }

    let bytes = unsafe { std::slice::from_raw_parts(html.cast::<u8>(), html_len) };
    let Ok(text) = std::str::from_utf8(bytes) else { unsafe { *out_err = 2 }; return std::ptr::null_mut() };

    match catch_unwind(AssertUnwindSafe(|| {
        mdream::html_to_markdown(text, mdream::HtmlToMarkdownOptions::default())
    })) {
        Ok(out) => {
            // Box<[u8]> has no spare capacity: (ptr, len) fully describes the allocation.
            let boxed: Box<[u8]> = out.into_bytes().into_boxed_slice();
            let len = boxed.len();
            unsafe { *out_len = len };
            Box::into_raw(boxed).cast::<u8>().cast::<c_char>()
        }
        Err(_) => { unsafe { *out_err = 3 }; std::ptr::null_mut() }
    }
}

/// # Safety
/// `ptr`/`len` must be exactly what `mdream_convert` returned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mdream_string_free(ptr: *mut c_char, len: usize) {
    if ptr.is_null() { return; }
    let slice = std::ptr::slice_from_raw_parts_mut(ptr.cast::<u8>(), len);
    drop(unsafe { Box::from_raw(slice) });
}
```

Note the pitfall this avoids: `String::from_raw_parts(ptr, len, len)` (and `Vec::from_raw_parts`)
require `capacity == len`; `shrink_to_fit()` is only a *request*, so that common pattern is
unsound. `Box<[u8]>` carries no capacity, so `(ptr, len)` is an exact, safe description.

Java side:

```java
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class MdreamNative {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle CONVERT, FREE;

    static {
        // Arena.global(): the library stays loaded for the JVM's lifetime.
        SymbolLookup lib = SymbolLookup.libraryLookup(Path.of(System.getProperty("mdream.lib")), Arena.global());
        CONVERT = LINKER.downcallHandle(lib.find("mdream_convert").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        FREE = LINKER.downcallHandle(lib.find("mdream_string_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    }

    public static String htmlToMarkdown(byte[] html) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {          // deterministic free, one thread
            MemorySegment in = arena.allocate(html.length, 8);
            MemorySegment.copy(html, 0, in, ValueLayout.JAVA_BYTE, 0, html.length); // one copy
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment err    = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment res = (MemorySegment) CONVERT.invokeExact(
                    in, (long) html.length, outLen, err);
            int e = err.get(ValueLayout.JAVA_INT, 0);
            if (e != 0) throw new IllegalStateException("mdream error " + e);

            long n = outLen.get(ValueLayout.JAVA_LONG, 0);
            byte[] out = res.reinterpret(n).toArray(ValueLayout.JAVA_BYTE); // copy #2
            FREE.invokeExact(res, n);                                       // Rust-owned buffer
            return new String(out, StandardCharsets.UTF_8);                 // decode #3
        }
    }
}
```

Measured: **337 µs** for the 166 KB fixture — identical to mdream's published Rust-native
0.34 ms, i.e. the boundary is invisible.

### 2.3 Streaming and upcalls (compiled and run)

C ABI: opaque handle + caller-owned callback. Rust:

```rust
pub struct MdreamStream { inner: mdream::MarkdownStreamProcessor }
pub type MdreamSink = extern "C" fn(ctx: *mut std::ffi::c_void, chunk: *const c_char, len: usize);

#[unsafe(no_mangle)]
pub extern "C" fn mdream_stream_new() -> *mut MdreamStream {
    Box::into_raw(Box::new(MdreamStream {
        inner: mdream::MarkdownStreamProcessor::new(mdream::HtmlToMarkdownOptions::default()),
    }))
}

/// # Safety
/// `handle` from mdream_stream_new; `chunk` readable for `len`; `sink` valid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mdream_stream_push_cb(
    handle: *mut MdreamStream, chunk: *const c_char, len: usize,
    sink: MdreamSink, ctx: *mut std::ffi::c_void,
) -> u32 {
    if handle.is_null() || sink as usize == 0 { return 1; }
    let bytes = unsafe { std::slice::from_raw_parts(chunk.cast::<u8>(), len) };
    let Ok(text) = std::str::from_utf8(bytes) else { return 2; };
    let out = unsafe { &mut *handle }.inner.process_chunk(text);
    // Valid only for the duration of the call: no ownership crosses the boundary.
    sink(ctx, out.as_ptr().cast::<c_char>(), out.len());
    0
}
```

Java:

```java
static void onChunk(MemorySegment ctx, MemorySegment chunk, long len) {
    try {                                   // MUST NOT throw: escaping = JVM abort
        if (len > 0 && len <= Integer.MAX_VALUE)
            sink.append(new String(chunk.reinterpret(len).toArray(ValueLayout.JAVA_BYTE),
                                   StandardCharsets.UTF_8));
    } catch (Throwable t) { /* record and stop streaming */ }
}

MethodHandle target = MethodHandles.lookup().findStatic(
        Mdream4j.class, "onChunk",
        MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, long.class));

try (Arena arena = Arena.ofConfined()) {
    MemorySegment stub = LINKER.upcallStub(target,
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            arena);
    MemorySegment h = (MemorySegment) STREAM_NEW.invokeExact();
    for (int off = 0; off < html.length; off += CHUNK) {
        int n = Math.min(CHUNK, html.length - off);
        MemorySegment.copy(html, off, in, ValueLayout.JAVA_BYTE, 0, n);
        if ((int) STREAM_PUSH_CB.invokeExact(h, in, (long) n, stub, MemorySegment.NULL) != 0)
            throw new IllegalStateException("stream error");
    }
    STREAM_FREE.invokeExact(h);
}
```

Measured: 28 chunks of 64 KiB over the 1.8 MB fixture, **6.56 ms** (one-shot 5.68 ms), upcall
count 28, no upcall-related overhead detectable. A no-op upcall costs **40 ns**.

For plugin hooks (filter/extraction/frontmatter callbacks) the same mechanism applies, but note
that mdream's Rust engine deliberately *does not* expose hook plugins (`AGENTS.md:96`: "Hook
plugins exist only in the JS engine. The Rust engine takes declarative options instead"). So the
FFM binding should expose the same declarative `PluginConfig` struct as a C struct/JSON blob, not
invent a callback API the core doesn't have.

### 2.4 Minimising copies (measured)

| Technique | Effect | Verdict |
|---|---|---|
| `arena.allocate(html.length)` + `MemorySegment.copy(byte[])` | one copy, 0.046 ms for 1.8 MB (≈39 GB/s) | **use this** |
| `arena.allocateFrom(String)` | one UTF-8 encode straight to native | use for `String` input |
| `MemorySegment.ofArray(bytes)` + `Linker.Option.critical(true)` | zero input copy, measured 2.5 % faster on 166 KB | **don't** — violates the documented "extremely short, no upcall" contract for a 0.3–6 ms call |
| Output: Rust-owned `Box<[u8]>` + `free` | 1 alloc + 1 copy + 1 extra downcall | simple, correct |
| Output: caller-owned buffer (`mdream_convert_into`) | measured 0–3 % faster, no allocation ownership | **recommended**: leak-proof, Arena owns the buffer |
| `res.reinterpret(n).toArray(...)` | 28 µs for a 43 KB result (≈8 % of conversion) | unavoidable for a `String`-returning API |

### 2.5 Measured costs (this machine)

| Measurement | Value |
|---|---|
| Bare downcall `(i64,i64)->i64`, tight loop | **3.6 ns/call** |
| No-op upcall from Rust into Java | **40 ns/call** |
| 37-byte document, full Java path (arena+copy+call+free+decode) | **0.75 µs** (0.64 µs with a reused arena) |
| 166 KB page | **0.337 ms** fresh arena / 0.331 ms reused |
| 430 KB page | **0.423 ms** |
| 1.81 MB page | **5.68 ms** |
| Streaming, 64 KiB chunks, 166 KB | 0.56–0.65 ms (1.7–1.9× one-shot) |
| Streaming, 64 KiB chunks, 1.81 MB | 6.4–6.6 ms (1.13× one-shot) |
| `libraryLookup` + `downcallHandle`, first time | 15.5 ms |
| … same, warm | 0.12 ms |
| JVM process start, empty `main` | 40 ms |
| JVM process start, one native conversion | **90 ms** (+50 ms for FFM class init + dlopen) |
| `libmdream_ffi.dylib` (LTO+strip) | 554 KB (277 KB gzip) |
| Upstream published Rust engine | 0.34 / 0.41 / 5.20 ms (same fixtures) |

Published cross-checks: JEP 454 targets overhead "comparable to, if not better than, JNI";
`zakgof/java-native-benchmark` (JMH, JDK 19) reports call-only 49.7 ns FFM vs 56.6 ns JNI;
GraalVM's docs note upcalls registered by *direct* method (class+name) are "orders of magnitude
faster" than descriptor-only upcalls in native image.

---

## 3. Rust side

### 3.1 Crate shape

```toml
[package]
name = "mdream-capi"
edition = "2024"           # mdream uses edition 2024

[lib]
crate-type = ["cdylib", "staticlib"]

[dependencies]
mdream = { path = "../core" }
```

* `cdylib` → `.dylib` / `.so` / `.dll`. Add `staticlib` only if you ever want a single-file
  native-image or a JNI shim.
* Edition 2024 **requires** `#[unsafe(no_mangle)]` and `unsafe extern "C"`; plain `#[no_mangle]`
  is a hard error (Rust Edition Guide, "Unsafe attributes").
* The shim must live in its **own** crate: `crates/core` has `unsafe_code = "deny"`, which is a
  feature — the auditable unsafe surface stays at ~200 LOC in one file.
* Keep the symbol prefix and a `mdream_abi_version() -> u32` so Java can fail fast on a stale
  library.

### 3.2 Panic policy

Facts from the Rustonomicon ("FFI and unwinding"):

* Unwinding across a non-`-unwind` ABI boundary **aborts the process** ("`panic` will cause the
  process to safely abort").
* `catch_unwind` catches only unwinding panics; with `panic = "abort"` a panic aborts regardless.
* `extern "C-unwind"` exists if you *want* unwinding to cross — you don't.

Recommendation: keep `panic = "unwind"` (mdream already sets it) and wrap every export in
`catch_unwind`, returning an error code. Verified: a deliberate panic returns code 3 to Java,
prints the Rust backtrace, and the JVM survives. `panic = "abort"` would kill the JVM on any
internal bug — strictly worse for a library. Do **not** use `extern "C-unwind"`.

If you choose `panic = "abort"` anyway (smaller binary, no unwind tables), you must also install a
panic hook to at least log before death; you cannot convert panics into Java exceptions.

### 3.3 ABI design: pick out-buffers + opaque handles

| Shape | Pros | Cons |
|---|---|---|
| `char* f(const char*, size_t)` returning owned memory | simplest | needs a `free`; two ownership domains; easy to leak; must return capacity or use `Box<[u8]>` |
| `isize f(in, len, out, cap)` caller-owned out-buffer | no ownership transfer, Arena-managed, leak-proof, one fewer call; `-needed` return supports grow-and-retry | caller must size the buffer (two-call or estimate `len/3`, mdream's own heuristic) |
| opaque `struct*` handle | natural for streaming/session state; cannot be confused with data | needs `new`/`free`; document thread affinity |
| `#[repr(C)]` struct for options | zero-parse, versionable | layout drift; add `size` field + version; strings need pointer+len pairs |
| JSON string for options | flexible, no layout risk | parse cost (~µs), another dependency unless you hand-roll; mdream's edge crate hand-rolls `JsValue` parsing to avoid serde |

For mdream specifically: one-shot = out-buffer; streaming = opaque handle; options = `#[repr(C)]`
struct with `struct_size: usize` first (so Java can pass a shorter struct from an older JAR), all
strings as `(ptr,len)` pairs, arrays as `(ptr,count)`. Return `MdreamResult` fields (markdown,
frontmatter, extracted) as a second out-buffer containing a tiny length-prefixed binary encoding,
or expose them as separate accessor calls on a result handle. Avoid JSON: mdream's core is
dependency-free and a Java-side JSON round-trip would add a dependency and ~10–30 µs.

### 3.4 Does mdream already give a reusable shape?

* `crates/node` (NAPI): gives you the *option mapping* (`to_core_opts`) and the `catch_panic`
  helper, both directly portable. It also proves the engine is embeddable and thread-safe.
* `crates/edge` (wasm): `process_chunk_bytes(&[u8]) -> String` already solves incremental UTF-8
  decoding with a carry buffer, including the "cut a 4-byte emoji across three pushes" case and a
  test for it. Copy that logic into the C ABI's `push_bytes` — otherwise Java users streaming
  from a socket will hit replacement characters at chunk boundaries.
* Neither exposes a C ABI; there is no `#[no_mangle]` anywhere in `crates/core`.

So: ~200–300 LOC of new Rust, reusing two design patterns, not two crates.

---

## 4. Packaging and distribution reality

### 4.1 The matrix

Minimum credible set (6), which matches what the repo already builds for NAPI:

| OS / arch | artifact | notes |
|---|---|---|
| macOS arm64 | `.dylib` | ad-hoc-signed by the linker; sign/notarize for distribution |
| macOS x64 | `.dylib` | still required for Intel Macs and Rosetta-less CI |
| Linux x64 glibc | `.so` | build against an old glibc baseline (manylinux2014 / zigbuild `--target …2.17`) |
| Linux arm64 glibc | `.so` | cross via `cargo-zigbuild` or native arm runners |
| Linux x64 musl | `.so` | **must** use `-C target-feature=-crt-static` (the repo already does this for musl NAPI); needs a musl JVM (Alpine) |
| Windows x64 | `.dll` | `+crt-static` (repo already does this) to avoid the VC++ redistributable |

Optional: Linux arm64 musl, Windows arm64. JDK 25 removed the 32-bit x86 port (JEP 503), so that
row disappears.

Cost: 6 × ~0.3–0.6 MB gzipped ≈ **1.7–2.5 MB** of JAR. For scale: jsoup 1.22.1 is a single
508 KB JAR. So the native route costs ~3–5× jsoup's download for universal coverage, and ~1× if
you ship only the current platform.

### 4.2 Bundling in a JAR

`SymbolLookup.libraryLookup` takes a **filesystem `Path`** — there is no load-from-memory in the
FFM API. So:

```java
static Path materialize(String resource, String suffix) throws IOException {
    Path dir = Path.of(System.getProperty("mdream.native.dir", System.getProperty("java.io.tmpdir")));
    Path out = dir.resolve("mdream4j-" + version + "-" + platform + suffix);
    if (Files.exists(out)) return out;                       // trust a pre-installed copy
    try (InputStream in = MdreamNative.class.getResourceAsStream(resource)) {
        Path tmp = Files.createTempFile(dir, "mdream4j", suffix);
        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE); // race-safe-ish
    }
    out.toFile().deleteOnExit();
    return out;
}
```

Operational consequences:

* **Read-only root filesystems / distroless**: extraction fails unless `mdream.native.dir` points
  at a writable volume or the library is pre-installed (document both; support
  `-Dmdream.library.path=/usr/lib/libmdream.so` to skip embedding).
* **`noexec` /tmp**: `dlopen` of a `noexec` file fails. Very common in hardened containers.
* **Antivirus / EDR on Windows** may quarantine a DLL written to `%TEMP%`.
* **`dlclose` hazards**: use `Arena.global()`. The repo already passes `-Wl,-z,nodelete` for gnu
  targets because unloading an embedded Rust library has bitten them in Node.
* **No JNI registration**: `libraryLookup` does not call `JNI_OnLoad` and is not tied to a class
  loader (JEP 454), which is actually a plus for app servers and OSGi.

### 4.3 jextract vs hand-written

`jextract` is **not in the JDK**: it is a separate early-access download (`jdk.java.net/jextract`,
build `25-jextract+2-4`, Nov 2025). It generates a large amount of boilerplate for a header-driven
API, and our surface is 6–8 functions. Recommendation: hand-write ~200 lines of bindings, keep a
small `mdream.h` as the contract, and optionally run jextract in CI only to diff/validate the
descriptors. Adding an EA toolchain to the release build for 8 functions is not worth it.

### 4.4 GraalVM native image

From GraalVM 25 docs (FFM API in Native Image):

* FFM support is **enabled by default** in GraalVM 25; disable with
  `-H:+UnlockExperimentalVMOptions -H:-ForeignAPISupport`.
* Downcalls/upcalls are supported **only** on Linux/x64, Linux/AArch64, macOS/AArch64,
  Windows/x64. **No macOS/x64, no Windows/AArch64.**
* Every downcall/upcall signature must be registered at build time
  (`reachability-metadata.json → foreign.downcalls/upcalls`, or a `Feature` using
  `RuntimeForeignAccess`); otherwise you get `MissingForeignRegistrationError` at runtime.
* Downcall handles **cannot** be created in a static initializer at build time ("the image build
  would freeze native addresses as constants").
* Prefer `directUpcalls` (class + method name) — "orders of magnitude faster" than
  descriptor-only upcalls.

Practical consequence: your loader's `static {}` block will run at runtime, not build time — fine,
but it means `native-image` users must add metadata for each signature you create. Ship the JSON
in `META-INF/native-image/<g>/<a>/reachability-metadata.json` so it is automatic.

### 4.5 JPMS, multi-release, reproducibility

* Put the FFM code in a **named module** (`module com.example.mdream`) and tell users
  `--enable-native-access=com.example.mdream`. JEP 472 explicitly recommends the module path over
  `ALL-UNNAMED` for least privilege. A library cannot self-grant access; the `Enable-Native-Access`
  manifest attribute only works for **executable** JARs and only with `ALL-UNNAMED`.
* Multi-release JARs (JEP 238) buy nothing here: FFM is final since 22 and you target 25.
* Reproducibility: pin the toolchain (`rust-toolchain.toml`), `cargo build --locked --offline`
  against a vendored registry (mdream has zero deps, so the vendor dir is tiny), and pass
  `--remap-path-prefix` to strip build paths. Cross-compilation: `cargo-zigbuild` (glibc
  baselines without old containers) or `cross` (Docker); Windows via `cargo-xwin` or a Windows
  runner. The repo's `release-native.yml` is already a working 13-target matrix to copy.
* Size/startup: 554 KB stripped per platform; +50 ms process startup for FFM init + `dlopen`
  (measured). AppCDS/AOT (JEP 514/515 in 25, JEP 516 in 26) does not cache FFM handles, only
  classes — it will shrink the 40 ms baseline, not the 50 ms native delta.

### 4.6 Supply chain / the "avoid jsoup CVEs" motivation

Measured facts, jsoup: 3 GitHub advisories in its history — GHSA-m72m-mhq2-9p6c (2021, **High**,
crafted input can hang/throw in the parser), GHSA-gp7f-rwcx-9369 (2022, Moderate, cleaner XSS),
GHSA-pmhh-3w7g-xqp8 (2026, Moderate, cleaner raw-text). Only the 2021 one affects plain parsing;
the other two affect the `Cleaner`/`Safelist`, which mdream would not use.

Honest analysis of the axis:

| | Pure Java port | Prebuilt native |
|---|---|---|
| Transitive deps visible to SCA | 0 | 0 (mdream core has zero deps) |
| Patch delivery | bump a version in `pom.xml`, Dependabot/Snyk sees it | bump a version; the `.so` itself is opaque to SCA |
| Binary auditability | source | disassembly + `cargo auditable` JSON + reproducible build |
| Blast radius of a bug | exception/`OutOfMemoryError` | possible JVM segfault |
| Trust boundary | JVM sandbox | native code in-process |

So: **shipping a prebuilt binary is worse on the auditability/CVE-response axis and better on the
dependency-count axis.** If "avoid jsoup CVEs" is the driver, the honest framing is: mdream has
*zero* dependencies and a fuzz harness, so the CVE surface is genuinely smaller — but that is true
of the Rust crate regardless of how you call it, and you can have the same zero-dependency
property with a self-contained Java port. Do not claim "native = fewer CVEs"; claim "zero
dependencies + fuzzing = fewer CVEs", and mitigate the binary-opacity problem with
`cargo auditable` (embeds the dependency list in the binary so scanners can read it),
`cargo deny`/`cargo audit` in CI, Sigstore/cosign signatures, and reproducible builds.

---

## 5. Performance expectations

### 5.1 What the evidence says

| Engine | 166 KB | 430 KB | 1.81 MB | Source |
|---|---|---|---|---|
| Rust native | 0.34 ms | 0.41 ms | 5.20 ms | upstream README |
| Rust via NAPI | 0.52 ms | 0.76 ms | 7.14 ms | upstream README |
| **Rust via FFM** | **0.337 ms** | **0.423 ms** | **5.68 ms** | this report |
| Pure JS engine | 3.26 ms | 6.38 ms | 57.2 ms | upstream README |
| jsoup parse only (Java, DOM) | 1.21 ms | 1.89 ms | 13.09 ms | this report |
| Hand-written Java byte scanner (no output) | 0.063 ms | 0.134 ms | 0.725 ms | this report |

Reading:

* **FFM adds nothing measurable.** 0.337 vs 0.34 ms upstream is within noise. NAPI costs
  0.18–1.9 ms; FFM does not.
* **The Java *language* is not the bottleneck.** A scalar Java byte loop scans 2.5–3.2 GB/s
  vs Rust's SWAR ~? on the same data. Scanning 1.8 MB takes 0.725 ms in Java; the Rust engine
  spends 5.2 ms on the same file, i.e. 86 % of the time is *output construction*, entity decoding,
  URL resolution and attribute handling — all of which a careful port can do reasonably well.
* **But a DOM approach is a trap**: jsoup's parse alone is 13 ms on the 1.8 MB file, 2.5× the
  entire Rust conversion. Any port must be streaming/byte-level, like the Rust engine, not
  jsoup-shaped.
* **Realistic port estimate: 2–5× slower than Rust** on 100 KB–2 MB pages after JIT warmup;
  the pure-JS engine (3.3–57 ms) is ~10× slower, and Java should beat it comfortably on this
  workload, but matching hand-written Rust/LTO without SIMD is unlikely. Vector API is still an
  incubator in JDK 25/26/27 (JEP 508/529/537 — tenth/eleventh/twelfth incubator), so a library
  cannot depend on it.
* **JIT warmup is real even on the native path**: the first upcall-streaming pass was 16.9 ms vs
  6.6 ms warm, because the Java-side plumbing (`toArray`, `String`, upcall stub) is interpreted.
  A cold CLI invocation pays that; a long-lived service does not.

### 5.2 When each wins

| Scenario | Winner |
|---|---|
| Few large conversions (≥100 KB), throughput-bound service | native, decisively (2–10×) |
| Many tiny conversions (<1 KB), latency-critical | native still wins, but the gap narrows: 0.75 µs total per call, of which ~0.4 µs is boundary/arena |
| Strict parity with upstream mdream output | native (it *is* upstream) |
| Android, no native artifacts allowed, single-JAR distribution | port |
| Cold CLI startup | port (native adds ~50 ms) |
| GraalVM native image, macOS x64 or Windows arm64 | port (FFM unsupported there) |
| Long-running service on Linux/macOS arm64 | native |

---

## 6. Operational downsides of the native route

1. **A segfault kills the JVM.** JEP 472 exists precisely because "calling native code can lead to
   arbitrary undefined behavior, including JVM crashes". Mitigation: mdream core is `deny(unsafe)`
   and fuzzed; the shim is ~200 LOC; fuzz the C ABI itself (`cargo fuzz` with a C-ABI target);
   consider a subprocess isolation mode for untrusted HTML if the threat model demands it.
2. **Debugging.** No Java frames inside Rust. You need `RUST_BACKTRACE=1`, `rust-gdb`/`lldb`,
   `hs_err_pid*.log` + core dumps, and symbolicated builds (don't `strip = true` for debug
   artifacts). Production `strip` is fine; keep a symbol server.
3. **Sanitizers.** ASAN/TSAN require nightly (`-Zsanitizer=address`) and a non-musl target; the
   repo's `fuzz.yml` already notes "a musl target cannot link the sanitizer". Miri cannot execute
   across the FFI boundary, so it validates the core but not the shim.
4. **Leak detection.** Rust-side allocations are invisible to the Java heap profiler. Use
   caller-owned out-buffers (Arena-managed) to make leaks structurally impossible, plus an RSS
   soak test and ASAN in CI.
5. **Cross-compilation CI.** 6–8 targets, some requiring Zig or Docker; macOS runners are slow and
   expensive; Windows needs MSVC. The repo already runs a 13-target matrix, so this is a known,
   solved cost — but it is a real cost.
6. **Corporate mirrors.** Maven classifiers work in Nexus/Artifactory and are how Netty,
   sqlite-jdbc, and JNA ship natives. But some organisations block native artifacts or require
   source builds; provide a `-Pfrom-source` profile and document it.
7. **Android: not viable with FFM.** The Android API has no `java.lang.foreign` (the official
   reference 404s). You would need JNI, i.e. a second binding, which is worse than FFM in every
   respect.
8. **Container images.** Read-only rootfs, `noexec` tmp, distroless (no `glibc` mismatch allowed),
   Alpine (musl JVM required), FIPS/hardened images. All solvable, all documentation surface.
9. **Native access warnings / future deny.** In JDK 25 (verified locally) an un-flagged run prints
   four `WARNING:` lines and still works; `--illegal-native-access=deny` throws
   `IllegalCallerException`. As of JDK 27 GA (Sept 2026) warn is still the default and no JEP has
   flipped it, but the direction is explicit ("will become the default in a future release").
   Every user of your library must eventually add `--enable-native-access=<module>`.
10. **AppCDS/AOT** helps Java classes only; the `dlopen` + `Linker` bootstrap cost (~50 ms
    process-level) is not cacheable, and GraalVM forbids build-time handle creation.

---

## 7. Hybrid option

Design: `Mdream` facade, two backends behind a package-private SPI.

```java
public interface Engine { String convert(byte[] html, Options o); StreamEngine stream(Options o); }

static Engine load() {
    if (Boolean.getBoolean("mdream.pureJava")) return new PureJavaEngine();
    try { return new NativeEngine(NativeLib.load()); }        // arch/ABI-version/hash checked
    catch (Throwable t) { logOnce(t); return new PureJavaEngine(); }
}
```

* Default pure-Java, opt-in native (`-Dmdream.engine=native`), or auto with a hard failure mode.
* **Parity is the price**: two engines means the differential harness becomes mandatory
  (byte-for-byte on the fixture corpus; the repo already has this concept for JS vs Rust).
* **Divergence is user-visible**: `-Dmdream.engine` must be in the bug-report template, and
  output-affecting differences must be treated as bugs.
* Bundle the native lib as a separate optional artifact (`mdream4j-native`) so pure-Java users
  never download 2 MB of binaries. This is the standard "optional accelerator" pattern
  (Netty `tcnative`, Lucene MMapDirectory, SQLite JDBC).
* If you build the port first, the facade makes the native backend a drop-in later; if you build
  FFM first, the facade costs ~1 day and gives you an escape hatch for Android/unsupported
  platforms. Either way, **build the facade interface first**.

Recommendation: ship B (native) as the default and only backend initially; add the facade
interface but do not write a second engine unless a customer requires Android or a zero-native
policy.

---

## 8. Decision matrix

Weights assume: a Java 25 library whose job is LLM-ready Markdown from web pages, used in
server-side JVM services on Linux/macOS, with correctness/parity as the top concern.

| Criterion | Weight | A: pure port | B: FFM | Notes |
|---|---|---|---|---|
| Output parity & upstream drift | 0.25 | 2 | **5** | 293 upstream commits/90 d; JS engine already needs parity tests |
| Time to first usable release | 0.15 | 1 | **5** | ~200 LOC shim + loader vs ~15–20k LOC + 903 tests |
| Runtime performance | 0.15 | 3 | **5** | native 0.34 ms vs est. 0.7–1.7 ms; FFM overhead ≈0 |
| Deployment simplicity | 0.15 | **5** | 2 | 1 JAR vs 6 binaries, tmp extraction, native-access flag |
| Operational safety / debugging | 0.10 | **5** | 2 | no segfaults, no cross-language stacks |
| Platform reach | 0.10 | **4** | 2 | Android, GraalVM (macOS x64/Win arm64), cold CLI |
| Security / auditability | 0.05 | **4** | 3 | opaque binary vs source; but zero deps + fuzz on both |
| Long-term maintenance | 0.05 | 2 | **4** | one engine vs three |
| **Weighted total** | 1.00 | **3.10** | **3.95** | |

Sensitivity: if "deployment simplicity" and "platform reach" are weighted 0.25 each (consumer
library, Android target), the port wins (≈3.6 vs 3.3). That is the only realistic scenario in
which A is the right call — and in that scenario you should also accept the permanent
maintenance burden of a third engine.

---

## 9. Suggested spike (3 days, with go/no-go gates)

**Day 1 — ABI + parity.**
1. Create `crates/capi` (~250 LOC): `mdream_convert`, `mdream_convert_into`, `mdream_string_free`,
   `mdream_stream_new/push_bytes/finish/free`, `mdream_abi_version`, `mdream_panic_test`.
   Reuse `crates/edge`'s UTF-8 carry logic for `push_bytes`.
2. Java binding (`com.example.mdream.native`) with `Arena` per call, handles cached, and a
   `--illegal-native-access=deny`-clean test run.
3. Parity harness: run all 8 core fixtures + 3 node fixtures + the `packages/mdream/test/unit`
   expectations through Rust-CLI and through Java-FFM; assert byte equality, including
   `frontmatter` and `extracted`.
   *Gate: 100 % byte-identical, including the 1.8 MB file and the streaming-vs-one-shot
   invariant (`join(chunks) == one-shot`).*

**Day 2 — packaging.**
4. Build the 6-target matrix in CI (copy `release-native.yml`); embed as classifiers plus a
   "bundle-all" JAR.
5. Loader with `mdream.native.dir` / `mdream.library.path` overrides, atomic extraction, SHA-256
   verification, and clear errors for wrong arch/missing lib/truncated lib.
6. Smoke tests in `debian:stable-slim`, `alpine` (musl JVM), `gcr.io/distroless/java25`, with a
   read-only rootfs and with `noexec` `/tmp`.
   *Gate: zero-config works on Debian/macOS/Windows; documented flags work on Alpine and
   read-only containers; failure modes produce exceptions, not crashes.*

**Day 3 — performance + robustness.**
7. JMH: one-shot (1 KB / 10 KB / 166 KB / 430 KB / 1.8 MB), streaming at 4 KiB/64 KiB/1 MiB
   chunks, 8-thread throughput, and reuse-vs-fresh arena.
8. Soak: 100k conversions, RSS sampled; ASAN build run over the same corpus; `cargo fuzz` on the
   C ABI for 10 minutes.
9. Cold start: `hyperfine` on a one-shot CLI (with and without AppCDS/AOT) to quantify the +50 ms.
   *Gate: ≤5 % overhead vs the Rust CLI on ≥166 KB inputs; RSS growth <5 MB over 100k
   conversions; cold start ≤ +60 ms; no ASAN findings; no leak with a 64 KiB `noexec` tmp.*

**In parallel, timebox 2 days on A** to falsify the estimate: port only the scanner + text +
headings + paragraphs + lists for the 166 KB fixture, measure, and extrapolate LOC/time. Kill it
if the subset exceeds 3× the native time or the LOC extrapolation exceeds ~12k.

**If both gates pass**, ship B (FFM) as the default; keep A as an explicit "no native" mode only if
a real user needs it. Record the decision in an ADR with these numbers.

---

## 10. Sources

Primary:
- JEP 454: Foreign Function & Memory API — https://openjdk.org/jeps/454
- JEP 472: Prepare to Restrict the Use of JNI — https://openjdk.org/jeps/472
- JDK 25 API: `Linker`, `Linker.Option`, `Arena`, `MemorySegment` — https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/
- JDK 25 Core Libraries Guide, "Foreign Function and Memory API" — https://docs.oracle.com/en/java/javase/25/core/foreign-function-and-memory-api.html
- JDK 25 project page (JEP list; JEP 503 32-bit x86 removal, JEP 508 Vector 10th incubator, JEP 514/515 AOT) — https://openjdk.org/projects/jdk/25/
- jextract early-access builds (separate download, 25-jextract+2-4) — https://jdk.java.net/jextract/
- GraalVM Native Image, "Foreign Function and Memory API" — https://www.graalvm.org/latest/reference-manual/native-image/native-code-interoperability/ffm-api/
- GraalVM reachability metadata, `foreign` section — https://www.graalvm.org/latest/reference-manual/native-image/metadata/
- The Rustonomicon, "FFI and unwinding" — https://doc.rust-lang.org/nomicon/ffi.html
- The Rust Edition Guide, "Unsafe attributes" — https://doc.rust-lang.org/edition-guide/rust-2024/unsafe-attributes.html
- jsoup security advisories — https://github.com/jhy/jsoup/security/advisories

Secondary (benchmarks, prior art):
- mdream README benchmarks — `/Users/fbricon/Dev/souk/mdream/README.md` §Benchmarks
- mdream repo facts — `crates/Cargo.toml`, `crates/core/src`, `crates/node/package.json`, `AGENTS.md`, `git log`
- `zakgof/java-native-benchmark` (JMH: FFM 49.7 ns vs JNI 56.6 ns call-only) — https://github.com/zakgof/java-native-benchmark
- Local spike sources: `…/T/opencode/ffm-spike/{src/lib.rs,FfmBench.java,CriticalBench.java,AbiBench.java,StreamBench.java,UpcallBench.java,UpcallCost.java,LoadCost.java,OneShot.java,JavaBaseline.java}`
