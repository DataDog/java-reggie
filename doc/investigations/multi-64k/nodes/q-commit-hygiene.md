---
id: q-commit-hygiene
type: question
status: answered
depends_on: []
supersedes: []
related: []
tags: [commit, spotless, git-hygiene]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Split spotless normalization hunks from feature hunks at commit? — ANSWERED

**Answer: split — and the feature hunks no longer exist (L2 dropped).**
2026-09-16, commit 715fb53 on feat/dd_backend_check: "chore: spotless normalization — javadoc
rewrap + license-header formatting" (24 files, +470/-352, content-only formatting). The stray
untracked doc/investigations/dd_backend_fit cairn export was deliberately NOT committed and
remains untracked in the worktree — decide its fate separately (move out of the repo or
gitignore; it is a cairn artifact, not repo content).
