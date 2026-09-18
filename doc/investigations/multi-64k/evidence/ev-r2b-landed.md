# ev-r2b-landed: case-insensitive + 1-char required facts (97ec426)

USER DIRECTIVE: "let's do r2b and then get fresh match and no-match bench numbers" (+ rust-vs-reggie
matched comparison interlude).

DIAGNOSIS (C2-CONverged re-sizing; methodology: generated methods need 10k+ invocations to reach
C2 — 200-rep probes measured C1 code and overstated reggie ~6x, rust/JDK unaffected → diagnosable):
no-match 2.66ms split GENERATED 1.32ms / Hybrid 0.58ms / BitState 0.45ms / NameEnriching 0.19ms.
Two fact families the R1 extractor silently dropped explain the top offenders: (1) global (?i)
patterns never yield literal facts (parser folds cased letters into char classes) — the
(?i)^.*kafka\.consume.*$ family ~320us; (2) no >=2-char literal — version-string family ~470us
(only '-') and [a-z]+_... (~78us, only '_').

LANDED (97ec426; suite + integration + span battery + fuzz oracle 10.5k checks/0 findings):
- ci facts: extract from the leading-(?i)-stripped pattern, scan ASCII case-insensitively (exact
  vs Java (?i) without (?u); ci scanning is the permissive side). PrefilteringMatcher gains
  asciiCaseInsensitive + allocation-free ASCII-ci indexOf.
- R1b: RequiredLiteralAnalyzer.requiredChar — soundness-by-construction AST walk (concat: any
  child's required char; alternation: char required by every branch; quantifier min>=1; groups
  pass through) — 1-char facts as last resort (absence falsifies every match; presence check is
  one indexOf).
- 7 test files updated to unwrap the (now much more common) PrefilteringMatcher wrapper via
  EngineRouting; the adversarial budget test asserts on the unwrapped engine (prefilter rejects
  before the budget guard fires — public contract still fails fast).

RUST-VS-REGGIE MATCHED (box, C2-converged, user interlude): totals reggie 484us vs rust 430 vs jdk
768 over 370 matched pairs; median per-pattern reggie/rust 0.35 (reggie faster on the typical
pair); total gap is 5 span-heavy patterns (PikeVM capture-lists, BitState greedy spans) where
rust's one-pass capture NFA is 10-16x faster — a different work item from position scanning.

MEASURED (workspace-jb, SAME-DAY single box state, all four commits re-run; controls flat:
rust no-match 1.72-1.77ms, jdk 30.5-33.2ms):
- reggieSweepNoMatch: 15,571 (5210bac) -> 2,475 (38eeb62 R1) -> 2,070 (b5cf63e R2) -> 983 (97ec426)
  = 15.8x vs baseline; reggie 1.79x AHEAD of rust (was 9.0x behind at baseline).
- reggieSweepMatched: 353 -> 357 -> 335 -> 338 (flat by design; 1.48x behind rust, 2.0x ahead jdk).
- Box drift note: the R1/R2 session box state was ~1.85x slower than today (same commits
  re-measured); cross-day absolute comparisons invalid — always normalize with same-run controls.
- Post-R1b no-match residual: BitState 384us (dominant), generated lanes ~475us spread,
  Hybrid COLLAPSED 582->58us, NameEnriching 61us.

R2B-PROPER DISPOSITION: the hybrid single-pass reinjection DFA was unnecessary for the corpus
(R1b+ci eliminated the family on inputs lacking the required char; residual 58us is inputs WITH
'-'). LazyDFACache.findFrom restart-at-death+1-without-rewalk appears leftmost-unsound in
general ('ab|b' on 'xaab' reasoning) — latent (no corpus pattern routes LAZY_DFA); see
q-lazydfa-findfrom-leftmost; must be fixed before routing anything through it.
