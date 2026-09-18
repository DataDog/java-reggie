---
id: ev-semver-vs-re2j
type: evidence
status: confirmed
depends_on: [ev-counted-loop-fix-verification, find-logs-backend-re2j-migration]
supersedes: []
related: [q-prod-readiness]
tags: [re2j, comparison, semver, perf, parity, baseline, migration]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Semver {0,256} family vs re2j 1.8 (the engine logs-backend uses today)

Question: does re2j support these patterns, and what is the reggie/re2j ratio?

**Support: yes, fully.** re2j 1.8 compiles the pattern (37ms; vs reggie 61ms, JDK 1ms)
and agrees with java.util.regex AND reggie on every probe input — all matches, all five
named-group captures (major/minor/patch/prerelease/buildmetadata byte-identical across
the three engines), and both >256 rejections (613-char prerelease-iteration and >256-digit).
re2j's bounded-repetition limit (1000) covers {0,256}. Probes: /tmp/census/{Re2jProbe,
Re2jCapture}.java, re2j jar from the reggie-benchmark module (com.google.re2j:re2j:1.8).

| input | re2j | reggie (750f848) | JDK | reggie/re2j | re2j/JDK |
|---|---|---|---|---|---|
| compile | 37ms | 61ms | 1ms | 1.65x | 37x |
| 5-char accept | 0.26us | 1.57us | 0.15us | **6.0x** | 1.7x |
| 22-char accept | 1.17us | 2.30us | 0.40us | 2.0x | 2.9x |
| 38-char accept | 1.74us | 3.00us | 0.45us | 1.7x | 3.9x |
| 313-char accept | 21.1us | 44.5us | 13.7us | 2.1x | 1.5x |
| 613-char REJECT | 39.7us | 111.6us | 26.3us | 2.8x | 1.5x |

Key observations:
- reggie is **2-3x slower than re2j** on realistic inputs (6x on trivial ones — but that is
  1.5us absolute). This is the interpreted-walk floor already noted in
  ev-counted-loop-fix-verification; not the 25-400x regression state.
- **re2j itself is slower than java.util.regex on this family** (21.1 vs 13.7us at 313 chars,
  39.7 vs 26.3us on the reject): RE2/J has no lazy DFA (unlike C++ RE2) — pure NFA simulation
  with ~2.6k-instruction programs from its own repetition expansion. So "keep on JDK" is the
  fastest option per-op for THIS family, but unbounded-time (ReDoS-exposed) and compile is 1ms.
- All three engines are bounded on these shapes EXCEPT JDK's backtracking on adversarial
  ambiguous shapes (where reggie fast-fails in ~230ms and re2j stays linear; JDK hangs).
- Migration implication (q-prod-readiness / find-logs-backend-re2j-migration): replacing re2j
  with reggie on this family costs ~2-3x match latency, gains the 98% native-coverage story,
  per-compile caching, and no re2j pattern-syntax surprises. Absolute worst case measured:
  112us vs re2j 40us on a 613-char invalid version string.
