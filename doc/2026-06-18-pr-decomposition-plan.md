# PR decomposition plan — `feat/pikevm-capture-cost` → 5 stacked PRs

Status: PLAN (awaiting approval before any branch/PR is created). Base: `origin/main` = `a924e431`
(2026-06-11). HEAD = `f06e984`. 74 commits / 106 files / +15,072 −608.

## Topology (important)
The branch is **not linear**: 41 first-parent commits + 33 second-parent commits absorbed by 12 merge
commits (squash-style integrations of `worktree-agent-*` branches). We therefore **keep history and
cut each PR branch at an exact first-parent SHA** — no cherry-pick/rebase (which would surface the
merge conflicts the merges already resolved). Every adjacent effort shares a hotspot file
(`PatternAnalyzer`, `PikeVMMatcher`, `FallbackPatternDetector`, `RuntimeCompiler`, the fuzz
budget/oracle), so all five **must stack** — none can target `origin/main` independently.

## The stack (cut SHAs)
```
origin/main a924e431
 └─ PR1  base=a924e431            head→ 5cb7555   substrate + routing groundwork
     └─ PR2  base=5cb7555         head→ 1133d2a   anchor/alt routing + B5/B12 backref
         └─ PR3  base=1133d2a     head→ 96d1e6d   capture-correctness wave (06-15)
             └─ PR4  base=96d1e6d head→ a9916bd   perf wave (06-17)
                 └─ PR5 base=a9916bd head→ f06e984 (HEAD)  O(n) drain + capture routing (06-18)
```

| PR | branch | base | head SHA | change type | net size |
|---|---|---|---|---|---|
| PR1 | `pr/1-reggieoption-fallback-substrate` | origin/main | `5cb7555` | New feature | ~62 files, +1,680/−379 (incl. docs) |
| PR2 | `pr/2-anchor-alt-routing-backref` | PR1 | `1133d2a` | Bug fix | code ~+650/−46 (+8,271 docs in `dfd070a`) |
| PR3 | `pr/3-capture-correctness-wave` | PR2 | `96d1e6d` | Bug fix | 19 files, +1,743/−149 |
| PR4 | `pr/4-perf-boolean-dfa-bitmap` | PR3 | `a9916bd` | Performance | 6 files, +1,497/−48 |
| PR5 | `pr/5-on-drain-capture-routing` | PR4 | `f06e984` | Performance | 6 files, +381/−86 (+283 bench) |

## Per-PR metadata (template-conforming bodies; checkboxes filled at creation time)

### PR1 — `feat: ReggieOption/@RegexPattern fallback substrate + PIKEVM routing groundwork`
- **What:** `ReggieOption` flag substrate (moved to `reggie-annotations`), `EnumSet<ReggieOption>` in
  `ReggieOptions`, throw-by-default fallback policy + `allowJdkFallback()`, `@RegexPattern`
  delegating-stub processor (native/delegate-pikevm/delegate-fallback), `compilePikeVm` staging
  entrypoint + name-map codec; fuzz oracle uses `ALLOW_JDK_FALLBACK`.
- **Motivation:** turn silent JDK fallback into an explicit, auditable policy — prerequisite for an
  honest native-coverage / ReDoS-safety claim.
- **Change type:** New feature. **Perf:** none.
- Contains merges `ee282fc 1735260 7bd6fdd a61fcdd d891b8d ba1e5b8` (kept as history).

### PR2 — `fix: anchor/alternation PIKEVM routing + B5/B12 backref support`
- **What:** route anchor-diluted capturing alternations & quantified-group conflicts to
  `PIKEVM_CAPTURE`; `compileHybrid` pre-check; revert over-broad PikeVM promotions; B5 lazy-quantifier
  guard (throw), B12 quantifier-prefix backref bytecode.
- **Motivation:** correct captures/matches for pattern classes the DFA strategies mishandled.
- **Change type:** Bug fix. Note: `dfd070a` adds 15 plan `.md` files (+8,271, docs only).
- Contains merges `496b56d f8b8c1d dc53a55 b9b208a 94d1626 1133d2a`.

### PR3 — `fix: capture correctness across DFA/MGG/GREEDY/RECURSIVE strategies`
- **What:** DFAUnrolled 1A/1B/1C (zero-width span, alternation binding, find-leftmost/empty-loop),
  PIKEVM anchor-in-quantified-group + trailing-empty-iteration, GREEDY_BACKTRACK suffix-char
  backtracking, MGG/DFA_SWITCH `\A`/`\z`/`\Z` anchors, RECURSIVE_DESCENT backref capture/restore,
  fuzz shrinker re-verify; `KNOWN_FINDINGS_BUDGET 50→18`.
- **Change type:** Bug fix. No merges (cleanest group).

### PR4 — `perf: CharSet ASCII bitmap, PikeVM prefilter, boolean find/matches DFAs`
- **What:** `CharSet.contains` O(1) ASCII bitmap; T1.2 first-char prefilter + O(1) accept; T1.4
  self-anchoring boolean-find DFA; T1.6 strict boolean `matches()` DFA; anchor-aware boolean DFA for
  leading `^`/`\A`. Capture-cost plan + benchmarks.
- **Change type:** Performance. **Perf:** boolean find/matches now beat re2j and JDK on the IAST
  patterns (e.g. many-optional `matches()` ~323→~38,000 ops/ms). No merges.

### PR5 — `perf: O(n) single-pass IAST drain + capture routing (Class A/E, MGG give-back)`
- **What:** single continuing-cursor PikeVM find (kills the per-start O(n²)); anchor origin pinned to
  0 (fixes `^`/`\A` findAll re-anchoring); boolean-DFA + over-approximating reject-DFA fast-reject;
  `findAll` group-span fuzz oracle (budget→69); MGG declines give-back; nullable-group-in-alternation
  and interacting-variable-length-alternations route to PikeVM.
- **Change type:** Performance (+ Bug fix). **Perf:** adversarial IAST drains 10–31× faster than re2j
  (URL/SQL/COMMAND zero-match drains ~8.5 µs vs re2j 84–306; was up to ~153,000 µs). No merges.

## Creation procedure (run after approval)
1. Ensure the AI label exists (create with a bright-blue color if missing).
2. For n = 1..5: `git branch pr/<n>-... <head-sha>`; `git push -u origin pr/<n>-...`.
3. `gh pr create --draft --base <prev-branch-or-main> --head pr/<n>-...` with the template-filled body
   (all applicable checkboxes ticked: tests pass / tests added / commits signed; blank line between
   sections; no unchecked boxes left dangling), `--label AI`.
4. Each PR's body links the stack ("Stacked on #<prev>"). PR1 base = `main`.

## Open decisions for approval
- **Docs commits** (`dfd070a` +8,271, `bda4b01` plan, the `docs/superpowers/plans/*.md`) ride inside
  their effort PRs. Alternative: strip them into a separate trailing "docs:" PR. Recommendation: keep
  in-effort (no conflict, low review cost) — but they inflate PR1/PR2/PR4 line counts.
- **`3a54bcc`** ("FQN cleanup and integration stash changes", mid-PR3) looks like a stash flush;
  optional to squash into its neighbor. Recommendation: leave as-is (preserves bisectability).
- **This turn's NEW uncommitted docs** (`doc/2026-06-18-*.md` research report + PR plan + the
  `docs/superpowers/plans/2026-06-18-*` capture design/plan) are NOT in the 74 commits. Decide
  whether to fold the research report into PR5 or land a separate docs PR.
- **spotlessApply** must be clean on every branch tip before pushing (run per-tip).
