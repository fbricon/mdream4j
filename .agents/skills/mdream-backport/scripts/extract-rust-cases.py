#!/usr/bin/env python3
"""Extract HTML inputs from upstream mdream's Rust test suites.

The upstream Rust tests are the richest source of edge cases for the port, but
they are written as Rust string literals.  This script tokenises the test
sources, decodes normal and raw string literals, and keeps every literal that
contains a tag-ish ``<`` (``<`` followed by a letter, ``/``, ``!`` or ``?``).

Caveat: many literals in those files are *expected Markdown outputs*, not HTML
inputs.  Expected outputs can contain ``<`` too (autolinks, raw HTML blocks).
Every emitted case is therefore only a candidate.  The script also writes a
``*.suspicious.txt`` report listing literals that look like Markdown, so they
can be checked against the surrounding test before being trusted.

Usage:
    python3 extract-rust-cases.py [--upstream DIR] [--output FILE]
                                  [--source FILE ...] [--stats]
                                  [--max-bytes N]

Defaults: the mdream4j checkout is ``$MDREAM_REPO``, else the nearest ancestor
of the current directory or of this script that contains
``tools/reference/Cargo.toml``. Upstream is ``$MDREAM_UPSTREAM`` or the sibling
``../mdream`` of that checkout; output is ``<skill>/work/rust-cases.jsonl``.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

DEFAULT_TEST_FILES = [
    "conversion.rs",
    "streaming_drain.rs",
    "whitespace_breaks.rs",
    "implied_end_tags.rs",
    "max_node_bytes.rs",
    "streaming_html_parity.rs",
]

TAG_ISH = re.compile(r"<[A-Za-z!/?]")
# Heuristics for "this is probably an expected Markdown output, not HTML input".
MARKDOWN_ISH = re.compile(r"(?:^|\n)\s*(?:#{1,6}\s|[-*+]\s|\d+\.\s|>|\||```|\[[^\]]*\]\()")


def _skill_dir() -> Path:
    return Path(__file__).resolve().parent.parent


def _default_repo() -> Path:
    env = os.environ.get("MDREAM_REPO")
    if env:
        return Path(env).expanduser().resolve()
    for start in (Path.cwd(), _skill_dir()):
        for candidate in (start, *start.parents):
            if (candidate / "tools" / "reference" / "Cargo.toml").is_file():
                return candidate
    return Path.cwd()


def _default_upstream() -> Path:
    env = os.environ.get("MDREAM_UPSTREAM")
    if env:
        return Path(env).expanduser()
    return _default_repo().parent / "mdream"


def _skip_line_comment(src: str, i: int) -> int:
    end = src.find("\n", i)
    return len(src) if end < 0 else end + 1


def _skip_block_comment(src: str, i: int) -> int:
    depth = 1
    i += 2
    while i < len(src) and depth:
        if src.startswith("/*", i):
            depth += 1
            i += 2
        elif src.startswith("*/", i):
            depth -= 1
            i += 2
        else:
            i += 1
    return i


def _skip_char_literal(src: str, i: int) -> int | None:
    """Return the index after a char literal, or None if this is a lifetime."""
    j = i + 1
    if j >= len(src):
        return None
    if src[j] == "\\":
        k = j + 1
        if k >= len(src):
            return None
        if src[k] == "u" and k + 1 < len(src) and src[k + 1] == "{":
            close = src.find("}", k + 2)
            if close < 0:
                return None
            k = close + 1
        elif src[k] == "x":
            k += 3
        else:
            k += 1
        if k < len(src) and src[k] == "'":
            return k + 1
        return None
    # A single Unicode scalar value, then a closing quote.
    if j + 1 < len(src) and src[j + 1] == "'":
        return j + 2
    return None


def _parse_raw_string(src: str, i: int) -> tuple[str, int] | None:
    """Parse ``r"..."`` / ``r#"..."#`` starting at ``r``."""
    j = i + 1
    hashes = 0
    while j < len(src) and src[j] == "#":
        hashes += 1
        j += 1
    if j >= len(src) or src[j] != '"':
        return None
    start = j + 1
    close = '"' + "#" * hashes
    end = src.find(close, start)
    if end < 0:
        return None
    return src[start:end], end + len(close)


def _parse_normal_string(src: str, i: int) -> tuple[str, int] | None:
    """Parse a normal Rust string literal starting at the opening quote."""
    out: list[str] = []
    j = i + 1
    while j < len(src):
        c = src[j]
        if c == '"':
            return "".join(out), j + 1
        if c != "\\":
            out.append(c)
            j += 1
            continue
        if j + 1 >= len(src):
            return None
        nxt = src[j + 1]
        simple = {
            "n": "\n",
            "r": "\r",
            "t": "\t",
            "0": "\0",
            "\\": "\\",
            '"': '"',
            "'": "'",
        }
        if nxt in simple:
            out.append(simple[nxt])
            j += 2
            continue
        if nxt == "x":
            try:
                out.append(chr(int(src[j + 2 : j + 4], 16)))
            except ValueError:
                return None
            j += 4
            continue
        if nxt == "u" and j + 2 < len(src) and src[j + 2] == "{":
            close = src.find("}", j + 3)
            if close < 0:
                return None
            try:
                out.append(chr(int(src[j + 3 : close], 16)))
            except ValueError:
                return None
            j = close + 1
            continue
        if nxt in "\r\n":
            # Line continuation: backslash, newline, then arbitrary whitespace.
            j += 2
            while j < len(src) and src[j] in " \t\r\n":
                j += 1
            continue
        # Unknown escape: Rust would reject it, but be permissive.
        out.append(nxt)
        j += 2
    return None


def iter_literals(src: str):
    """Yield every Rust string literal value in ``src``."""
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            i = _skip_line_comment(src, i)
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i = _skip_block_comment(src, i)
            continue
        if c == "'":
            after = _skip_char_literal(src, i)
            i = after if after is not None else i + 1
            continue
        # Byte/C string prefixes: b"..." / br#"..."# / c"...".
        if c in "bBcC" and i + 1 < n:
            nxt = src[i + 1]
            if nxt == '"':
                parsed = _parse_normal_string(src, i + 1)
                if parsed:
                    yield parsed[0]
                    i = parsed[1]
                    continue
            if nxt == "r":
                parsed = _parse_raw_string(src, i + 1)
                if parsed:
                    yield parsed[0]
                    i = parsed[1]
                    continue
        if c == "r" and i + 1 < n and (src[i + 1] == '"' or src[i + 1] == "#"):
            parsed = _parse_raw_string(src, i)
            if parsed:
                yield parsed[0]
                i = parsed[1]
                continue
        if c == '"':
            parsed = _parse_normal_string(src, i)
            if parsed:
                yield parsed[0]
                i = parsed[1]
                continue
        i += 1


def extract_file(path: Path, max_bytes: int) -> tuple[list[str], int]:
    source = path.read_text(encoding="utf-8")
    kept: list[str] = []
    seen: set[str] = set()
    total = 0
    for literal in iter_literals(source):
        total += 1
        if not literal or len(literal.encode("utf-8")) > max_bytes:
            continue
        if not TAG_ISH.search(literal):
            continue
        if literal in seen:
            continue
        seen.add(literal)
        kept.append(literal)
    return kept, total


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, default=_default_upstream())
    parser.add_argument("--output", type=Path, default=_skill_dir() / "work" / "rust-cases.jsonl")
    parser.add_argument(
        "--source",
        type=Path,
        action="append",
        default=None,
        help="test source file(s); default: the six upstream test suites",
    )
    parser.add_argument("--max-bytes", type=int, default=2 * 1024 * 1024)
    parser.add_argument("--stats", action="store_true")
    args = parser.parse_args(argv)

    if args.source:
        sources = args.source
    else:
        sources = [args.upstream / "crates" / "core" / "tests" / name for name in DEFAULT_TEST_FILES]
    missing = [str(p) for p in sources if not p.is_file()]
    if missing:
        parser.error("missing source file(s): " + ", ".join(missing))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    suspicious_path = args.output.with_suffix(args.output.suffix + ".suspicious.txt")

    records: list[dict] = []
    suspicious: list[str] = []
    for path in sources:
        kept, total = extract_file(path, args.max_bytes)
        stem = path.stem
        for index, literal in enumerate(kept, start=1):
            case_id = f"rust-{stem}-{index:03d}"
            records.append({"id": case_id, "html": literal, "options": {}})
            if MARKDOWN_ISH.search(literal):
                suspicious.append(case_id)
        if args.stats:
            print(
                f"{path.name}: {total} literals, {len(kept)} kept after tag filter + dedupe",
                file=sys.stderr,
            )

    with args.output.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")

    suspicious_path.write_text(
        "# Cases whose literal looks like an expected Markdown output.\n"
        "# Confirm each against the test that contains it before trusting it.\n"
        + "\n".join(suspicious)
        + ("\n" if suspicious else ""),
        encoding="utf-8",
    )

    print(f"wrote {len(records)} cases to {args.output}", file=sys.stderr)
    print(f"wrote {len(suspicious)} suspicious ids to {suspicious_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
