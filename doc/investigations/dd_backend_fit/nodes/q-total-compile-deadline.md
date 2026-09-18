---
id: q-total-compile-deadline
type: question
status: resolved
depends_on: [find-fixes-complete]
supersedes: []
related: [find-semver-exponential-compile]
tags: [design, deadline, dos, untrusted]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Should Reggie enforce a TOTAL-compile deadline (not per-determinization)?

**RESOLVED 2026-09-16**: RESOLVED by commit b0c5615: -Dreggie.compile.totalDeadlineMs (default 10s, 0 disables), ThreadLocal clamp into every determinization, between-phase checks; NFA-pass-through design nuance (PikeVM/BitState complete anyway — JDK fallback would move DoS to match time; JDK hangs on the 12x bomb at match time).


The 10s determinization deadline is per buildDFA call. PatternAnalyzer runs
several determinizations per compile (analysis + strategy routing + lookahead
sub-NFAs), each on a fresh SubsetConstructor with a fresh deadline — so total
compile time is bounded only by passes x 10s. The 12x (a|b){0,256} bomb still
takes ~10-15s (correct, bounded, but too slow for request-driven compile of
untrusted patterns like profiling-backend's UserDefinedRegex).

Design question: a compile-level wall-clock budget enforced in
RuntimeCompiler.compileInternal (throwing UnsupportedPatternException /
fallback on expiry) — probably as a ReggieOptions knob with a sensible
default, since consumers of untrusted patterns want ~1-2s, not 10-15s.
Follow-up API design decision, not blocking the current release.
