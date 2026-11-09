#!/usr/bin/env python3
"""Compare one-shot Java probe results with the Rust reference JSONL.

``--java`` is the binary written by ``Probe.java`` (``MD4JOUT1``).
``--reference`` is the JSONL written by ``mdream-golden`` (the
``mdream4j/src/test/resources/golden/expected.jsonl`` shape: ``{"id", "html",
"options", "outputs": {"markdown", "text", "html", "truncated"}}``).  Flat
records with the format as a top-level key are also accepted.

Exit status: 0 when every case matches, 1 when there are mismatches (unless
``--allow-mismatch`` is passed), 2 on usage errors.

Usage:
    python3 compare.py --java java.bin --reference rust-golden.jsonl \
        --format markdown --ids-out mismatch-ids.txt
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

MAGIC = b"MD4JOUT1"


def read_string(inp) -> str:
    raw = inp.read(4)
    if len(raw) != 4:
        raise SystemExit("truncated result file (string length)")
    (length,) = struct.unpack(">I", raw)
    if length > (1 << 30):
        raise SystemExit(f"implausible string length: {length}")
    data = inp.read(length)
    if len(data) != length:
        raise SystemExit("truncated result file (string body)")
    return data.decode("utf-8")


def read_results(path: Path) -> dict[str, dict]:
    results: dict[str, dict] = {}
    with path.open("rb") as inp:
        magic = inp.read(len(MAGIC))
        if magic != MAGIC:
            raise SystemExit(f"{path}: bad magic {magic!r}, expected {MAGIC!r}")
        raw = inp.read(4)
        if len(raw) != 4:
            raise SystemExit(f"{path}: truncated count")
        (count,) = struct.unpack(">I", raw)
        for _ in range(count):
            case_id = read_string(inp)
            status_raw = inp.read(1)
            if len(status_raw) != 1:
                raise SystemExit(f"{path}: truncated status for {case_id}")
            status = status_raw[0]
            output = read_string(inp)
            truncated_raw = inp.read(1)
            if len(truncated_raw) != 1:
                raise SystemExit(f"{path}: truncated flag for {case_id}")
            results[case_id] = {
                "status": status,
                "output": output,
                "truncated": truncated_raw[0] != 0,
            }
    return results


def load_reference(path: Path, fmt: str) -> dict[str, dict]:
    reference: dict[str, dict] = {}
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                raise SystemExit(f"{path}:{line_number}: {error}") from error
            case_id = record.get("id")
            if case_id is None:
                raise SystemExit(f"{path}:{line_number}: record has no id")
            outputs = record.get("outputs")
            if isinstance(outputs, dict):
                output = outputs.get(fmt)
                truncated = outputs.get("truncated")
            else:
                output = record.get(fmt)
                truncated = record.get("truncated")
            reference[case_id] = {"output": output, "truncated": truncated}
    return reference


def read_ids(path: Path | None) -> set[str] | None:
    if path is None:
        return None
    ids = set()
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line and not line.startswith("#"):
                ids.add(line)
    return ids


def snippet(text: str | None, limit: int) -> str:
    if text is None:
        return "<missing>"
    text = text.replace("\r", "\\r")
    if len(text) > limit:
        text = text[:limit] + f"… ({len(text)} chars)"
    return json.dumps(text, ensure_ascii=False)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--format", choices=["markdown", "text", "html"], default="markdown")
    parser.add_argument("--ids-file", type=Path, default=None, help="only compare these ids")
    parser.add_argument("--ids-out", type=Path, default=None, help="write mismatching ids here")
    parser.add_argument("--json-out", type=Path, default=None)
    parser.add_argument("--max-report", type=int, default=20)
    parser.add_argument("--snippet", type=int, default=300)
    parser.add_argument("--no-truncated", action="store_true", help="ignore the truncated flag")
    parser.add_argument("--allow-mismatch", action="store_true")
    args = parser.parse_args(argv)

    java = read_results(args.java)
    reference = load_reference(args.reference, args.format)
    only = read_ids(args.ids_file)

    ids = sorted(set(reference) | set(java))
    if only is not None:
        ids = [case_id for case_id in ids if case_id in only]

    mismatches: list[dict] = []
    for case_id in ids:
        expected = reference.get(case_id)
        actual = java.get(case_id)
        if expected is None:
            mismatches.append(
                {"id": case_id, "kind": "missing-reference", "expected": None,
                 "actual": actual["output"] if actual else None}
            )
            continue
        if actual is None:
            mismatches.append(
                {"id": case_id, "kind": "missing-java", "expected": expected["output"],
                 "actual": None}
            )
            continue
        if actual["status"] != 0:
            mismatches.append(
                {"id": case_id, "kind": "java-error", "expected": expected["output"],
                 "actual": actual["output"]}
            )
            continue
        if expected["output"] != actual["output"]:
            mismatches.append(
                {"id": case_id, "kind": "output", "expected": expected["output"],
                 "actual": actual["output"]}
            )
            continue
        if (
            not args.no_truncated
            and args.format == "markdown"
            and expected["truncated"] is not None
            and bool(expected["truncated"]) != actual["truncated"]
        ):
            mismatches.append(
                {"id": case_id, "kind": "truncated", "expected": str(expected["truncated"]),
                 "actual": str(actual["truncated"])}
            )

    print(
        f"compared {len(ids)} cases (format={args.format}), "
        f"{len(mismatches)} mismatch(es)"
    )
    for index, mismatch in enumerate(mismatches[: args.max_report], start=1):
        print(f"\n[{index}] {mismatch['id']}  ({mismatch['kind']})")
        print(f"  expected: {snippet(mismatch['expected'], args.snippet)}")
        print(f"  actual:   {snippet(mismatch['actual'], args.snippet)}")
    if len(mismatches) > args.max_report:
        print(f"\n… {len(mismatches) - args.max_report} more not shown")

    if args.ids_out:
        args.ids_out.parent.mkdir(parents=True, exist_ok=True)
        args.ids_out.write_text(
            "".join(mismatch["id"] + "\n" for mismatch in mismatches), encoding="utf-8"
        )
        print(f"\nwrote {len(mismatches)} ids to {args.ids_out}")

    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(
            json.dumps(mismatches, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    if mismatches and not args.allow_mismatch:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
