#!/usr/bin/env python3
"""Scratch-file gate (AGENTS.md: task-scoped documents go in doc/temp/, gitignored).

Usage: check-scratch-files.py BASE [HEAD]
Fails when commits in BASE...HEAD add task-scoped or agent-tool scratch files:
  - anything under doc/temp/ (force-added past .gitignore)
  - agent review-tool scratch under .sphinx/address/
  - dated task write-ups directly under doc/ (doc/<name>-YYYY-MM-DD.md)
Design docs keep the established date-PREFIX form (doc/YYYY-MM-DD-<topic>.md).
Exit 0 = pass, 1 = gate failure, 2 = usage/git error.
"""
import re
import subprocess
import sys

RULES = [
    (re.compile(r"^doc/temp/"), "task-scoped doc must stay in gitignored doc/temp/"),
    (re.compile(r"^\.sphinx/address/"), "agent review-tool scratch plan"),
    (re.compile(r"^doc/[^/]+-\d{4}-\d{2}-\d{2}\.md$"), "dated task write-up; move to doc/temp/"),
]


def main(argv):
    if len(argv) not in (2, 3):
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    base, head = argv[1], argv[2] if len(argv) == 3 else "HEAD"
    try:
        out = subprocess.run(
            ["git", "diff", "--name-only", "--no-renames", "--diff-filter=A", "%s...%s" % (base, head)],
            check=True, capture_output=True, text=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as e:
        print("check-scratch-files: git diff failed: %s" % e, file=sys.stderr)
        return 2
    bad = []
    for path in out.splitlines():
        for rx, why in RULES:
            if rx.search(path):
                bad.append("%s  (%s)" % (path, why))
                break
    if bad:
        print("Scratch-file gate FAILED — remove these from the commit range (git rm --cached):")
        for b in bad:
            print("  - " + b)
        return 1
    print("Scratch-file gate OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
