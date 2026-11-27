#!/usr/bin/env python3
"""Fail if a Supabase secret would reach the repository or the APK.

The app may contain the project URL and the *publishable* key only: that key maps
to the `anon`/`authenticated` roles, and every table is owner-only under RLS. A
service-role/secret key, a database password or a JWT with role `service_role`
grants full access to every account's ledger and must never be committed.

Run locally or in CI:
    python3 scripts/check_secrets.py
"""
from __future__ import annotations

import base64
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SKIP_DIRS = {
    ".git", ".gradle", "build", ".idea", "node_modules",
    "__pycache__", ".pytest_cache", ".venv",
}
TEXT_SUFFIXES = {
    ".kt", ".kts", ".java", ".xml", ".json", ".md", ".yml", ".yaml",
    ".properties", ".pro", ".sh", ".py", ".txt", ".gradle", ".toml",
}
BINARY_SUFFIXES = {".apk", ".aab", ".jar", ".so", ".png", ".jpg", ".jpeg", ".webp"}

# Patterns that always mean a secret leaked.
ALWAYS = [
    (re.compile(r"sb_secret_[A-Za-z0-9_\-]{8,}"), "new-style Supabase secret key"),
    (re.compile(r"service_role\s*[:=]\s*['\"]eyJ[A-Za-z0-9_\-\.]+"), "service_role JWT assigned"),
    (re.compile(r"postgres(?:ql)?://[^\s:/]+:[^\s@]+@"), "database URL with a password"),
    (re.compile(r"SUPABASE_SERVICE_ROLE_KEY\s*[:=]\s*['\"]?[A-Za-z0-9_\-]{10,}"),
     "SUPABASE_SERVICE_ROLE_KEY value"),
]

JWT = re.compile(r"eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}")


def jwt_role(token: str) -> str | None:
    """Return the `role` claim without verifying the signature (leak check only)."""
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        claims = json.loads(base64.urlsafe_b64decode(payload))
    except Exception:
        return None
    role = claims.get("role")
    return role if isinstance(role, str) else None


def text_files():
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        yield path


def scan() -> list[str]:
    problems: list[str] = []
    for path in text_files():
        rel = path.relative_to(ROOT)
        suffix = path.suffix.lower()
        if suffix in BINARY_SUFFIXES:
            # Only look for the unambiguous new-style prefix in binaries.
            data = path.read_bytes()
            if b"sb_secret_" in data:
                problems.append(f"{rel}: contains an sb_secret_ key")
            continue
        if suffix not in TEXT_SUFFIXES and path.name not in {"gradlew", "gradlew.bat"}:
            continue
        try:
            lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
        except OSError:
            continue
        for number, line in enumerate(lines, start=1):
            for pattern, label in ALWAYS:
                if pattern.search(line):
                    problems.append(f"{rel}:{number}: {label}")
            for token in JWT.findall(line):
                if jwt_role(token) == "service_role":
                    problems.append(f"{rel}:{number}: JWT with role service_role")
    return problems


def main() -> int:
    problems = scan()
    if problems:
        print("SECRET CHECK FAILED", file=sys.stderr)
        for item in problems:
            print(f"  - {item}", file=sys.stderr)
        print(
            "Remove the value, rotate it in Supabase, and keep secrets in the "
            "project's environment or GitHub secrets.",
            file=sys.stderr,
        )
        return 1
    print("Secret check passed: no service-role key, secret key or database password.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
