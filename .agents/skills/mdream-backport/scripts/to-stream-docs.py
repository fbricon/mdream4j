#!/usr/bin/env python3
"""Convert a JSONL case file to the streaming binary read by StreamProbe.java.

Binary format (all integers big-endian)::

    magic  "MD4JSTR1"                     8 bytes
    u32    case count
    repeat:
      u32  id length,      id UTF-8
      u32  html length,    html UTF-8
      u32  options length, options JSON UTF-8
      u32  chunk size (in UTF-8 bytes, like the Rust ``stream-golden``)

The chunk size is taken from the case's ``chunkSize`` field when present,
otherwise from ``--chunk-size`` (default 7).  ``--chunk-size`` always wins when
given.  Use ``--emit-jsonl`` to write the same cases with an explicit
``chunkSize`` field for ``stream-golden``; both sides must chunk identically.

Usage:
    python3 to-stream-docs.py cases.jsonl stream.bin --chunk-size 7 \
        --emit-jsonl stream-cases.jsonl
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

MAGIC = b"MD4JSTR1"
MAX_U32 = (1 << 32) - 1
DEFAULT_CHUNK = 7


def load_cases(path: Path) -> list[dict]:
    cases: list[dict] = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                case = json.loads(line)
            except json.JSONDecodeError as error:
                raise SystemExit(f"{path}:{line_number}: {error}") from error
            if not isinstance(case, dict) or "id" not in case or "html" not in case:
                raise SystemExit(f"{path}:{line_number}: case needs 'id' and 'html'")
            cases.append(case)
    return cases


def encode(text: str) -> bytes:
    data = text.encode("utf-8")
    if len(data) > MAX_U32:
        raise SystemExit(f"field too large for u32: {len(data)} bytes")
    return data


def chunk_for(case: dict, forced: int | None) -> int:
    if forced is not None:
        return forced
    value = case.get("chunkSize", DEFAULT_CHUNK)
    if not isinstance(value, int) or value <= 0:
        raise SystemExit(f"case {case['id']}: chunkSize must be a positive integer")
    return value


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--chunk-size", type=int, default=None)
    parser.add_argument(
        "--emit-jsonl",
        type=Path,
        default=None,
        help="also write the cases with an explicit chunkSize for stream-golden",
    )
    args = parser.parse_args(argv)

    if args.chunk_size is not None and args.chunk_size <= 0:
        parser.error("--chunk-size must be positive")

    cases = load_cases(args.input)
    args.output.parent.mkdir(parents=True, exist_ok=True)

    with args.output.open("wb") as out:
        out.write(MAGIC)
        out.write(struct.pack(">I", len(cases)))
        for case in cases:
            chunk = chunk_for(case, args.chunk_size)
            case_id = encode(str(case["id"]))
            html = encode(str(case["html"]))
            options = json.dumps(
                case.get("options") or {}, separators=(",", ":"), ensure_ascii=False
            )
            options_bytes = encode(options)
            out.write(struct.pack(">I", len(case_id)))
            out.write(case_id)
            out.write(struct.pack(">I", len(html)))
            out.write(html)
            out.write(struct.pack(">I", len(options_bytes)))
            out.write(options_bytes)
            out.write(struct.pack(">I", chunk))

    if args.emit_jsonl:
        args.emit_jsonl.parent.mkdir(parents=True, exist_ok=True)
        with args.emit_jsonl.open("w", encoding="utf-8") as handle:
            for case in cases:
                emitted = dict(case)
                emitted["chunkSize"] = chunk_for(case, args.chunk_size)
                handle.write(json.dumps(emitted, ensure_ascii=False) + "\n")
        print(f"wrote streaming cases to {args.emit_jsonl}", file=sys.stderr)

    print(f"wrote {len(cases)} cases to {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
