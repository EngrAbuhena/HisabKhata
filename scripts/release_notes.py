#!/usr/bin/env python3
"""Print the exact Keep a Changelog section for a v-prefixed Git tag."""
from pathlib import Path
import re
import sys


def main() -> int:
    if len(sys.argv) != 2 or not re.fullmatch(r"v[0-9A-Za-z][0-9A-Za-z.+_-]*", sys.argv[1]):
        print("Expected a v-prefixed release tag", file=sys.stderr)
        return 2
    version = sys.argv[1][1:]
    lines = Path("CHANGELOG.md").read_text(encoding="utf-8").splitlines()
    header = re.compile(r"^##\s+\[" + re.escape(version) + r"\](?:\s|$)")
    start = next((i for i, line in enumerate(lines) if header.match(line)), None)
    if start is None:
        print(f"No CHANGELOG section found for {version}", file=sys.stderr)
        return 1
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    print("\n".join(lines[start:end]).rstrip())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
