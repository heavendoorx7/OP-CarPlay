#!/usr/bin/env python3
"""Screen the Git publication set, not the vehicle or the private research tree."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
IGNORED = {".git", ".gradle", ".kotlin", ".cxx", ".idea", "build", "dist", "__pycache__"}
FORBIDDEN = {".apk", ".aab", ".jks", ".keystore", ".pk8", ".p7b", ".pem", ".p12", ".pfx", ".command"}
PATTERNS = [
    ("private key material", re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/=]{20}")),
    ("GitHub credential", re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{32,}|github_pat_[A-Za-z0-9_]{40,})")),
    ("personal filesystem path", re.compile(rb"/(?:Users|Volumes)/[^\s\"']+")),
]


def candidates():
    try:
        # Includes ignored files accidentally force-added to Git.
        raw = subprocess.check_output(
            ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
            cwd=ROOT, stderr=subprocess.DEVNULL,
        )
        return sorted({ROOT / x.decode() for x in raw.split(b"\0") if x})
    except subprocess.CalledProcessError:
        return sorted(p for p in ROOT.rglob("*") if p.is_file() and not (set(p.relative_to(ROOT).parts) & IGNORED))


def main():
    errors = []
    paths = candidates()
    for p in paths:
        rel = str(p.relative_to(ROOT))
        if p.is_symlink():
            errors.append((rel, "symlink"))
            continue
        if p.suffix.lower() in FORBIDDEN or p.name in {"local.properties", ".env", "signing.env"}:
            errors.append((rel, "private/deployment artifact"))
        if p.name.endswith((".zip", ".tgz", ".img", ".dex", ".so")):
            errors.append((rel, "binary archive or extracted vendor material"))
        data = p.read_bytes()
        if len(data) > 20 * 1024 * 1024:
            errors.append((rel, "unexpected large file"))
        for name, pattern in PATTERNS:
            if pattern.search(data):
                errors.append((rel, name))
    if errors:
        for path, reason in errors:
            print(f"{path}: {reason}", file=sys.stderr)
        return 1
    print(f"Publication screening passed: {len(paths)} files; no matched private artifacts or credential patterns.")
    print("This is a publication check, not a complete security audit.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
