#!/usr/bin/env python3
"""Convert a JSONL case file to the length-prefixed binary read by Probe.java.

Binary format (all integers big-endian)::

    magic  "MD4JDOC1"                     8 bytes
    u32    case count
    repeat:
      u32  id length,      id UTF-8
      u32  html length,    html UTF-8
      u32  options length, options JSON UTF-8

Usage:
    python3 to-docs.py cases.jsonl cases.bin

The options string is the compact JSON object from the case (``{}`` when
absent).  The matching writer/reader is ``scripts/Probe.java``.
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

MAGIC = b"MD4JDOC1"
MAX_U32 = (1 << 32) - 1


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


def write_case(out, case: dict, extra: bytes = b"") -> None:
    case_id = encode(str(case["id"]))
    html = encode(str(case["html"]))
    options = json.dumps(case.get("options") or {}, separators=(",", ":"), ensure_ascii=False)
    options_bytes = encode(options)
    out.write(struct.pack(">I", len(case_id)))
    out.write(case_id)
    out.write(struct.pack(">I", len(html)))
    out.write(html)
    out.write(struct.pack(">I", len(options_bytes)))
    out.write(options_bytes)
    out.write(extra)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args(argv)

    cases = load_cases(args.input)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as out:
        out.write(MAGIC)
        out.write(struct.pack(">I", len(cases)))
        for case in cases:
            write_case(out, case)
    print(f"wrote {len(cases)} cases to {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
