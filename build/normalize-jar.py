#!/usr/bin/env python3
"""Rewrite a JAR with stable entry order and timestamps."""

from __future__ import annotations

import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo


def normalize(source: Path, destination: Path) -> None:
    seen: set[str] = set()
    with ZipFile(source, "r") as source_zip, ZipFile(
        destination, "w", compression=ZIP_DEFLATED, compresslevel=9
    ) as destination_zip:
        entries = sorted(source_zip.infolist(), key=lambda item: item.filename)
        for entry in entries:
            if entry.filename in seen:
                continue
            seen.add(entry.filename)
            normalized = ZipInfo(entry.filename, date_time=(2000, 1, 1, 0, 0, 0))
            normalized.create_system = 3
            normalized.external_attr = entry.external_attr
            normalized.compress_type = ZIP_STORED if entry.is_dir() else ZIP_DEFLATED
            destination_zip.writestr(normalized, source_zip.read(entry))


def main() -> int:
    if len(sys.argv) != 3:
        print(f"usage: {sys.argv[0]} SOURCE_JAR DESTINATION_JAR", file=sys.stderr)
        return 64
    normalize(Path(sys.argv[1]), Path(sys.argv[2]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
