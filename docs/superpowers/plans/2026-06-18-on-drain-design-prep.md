# O(n) find-all / drain — design preparation

Status: DESIGN PREP COMPLETE — research-backed direction chosen (approach A); ready for the design→review loop
Date: 2026-06-18
Goal: make Reggie's find-all (the IAST tokenizer "drain") O(n) total, so Reggie can replace re2j.

## Why this is the real target
The representative `IastTokenizerDrainBenchmark` (malformed 512–2048B payloads, full match-drain)
showed Reggie is **catastrophically O(n²)** on adversarial inputs (URL_QUESTION_RUN ~130,000µs,
COMMAND_BLANK_LINES ~153,000µs at 2048B — ~560–860× slower than re2j, worse than JDK), loses to
re2j on benign URL/COMMAND drains, and only wins on SQL. Boolean find/matches (this session's
shipped wins) are O(n) and beat re2j — but the **drain** is the IAST workload, and it's O(n²).

## Internal grounding — where the O(n²) lives (verified)
`ReggieMatcher.findAll` (`:653-669`) loops `findMatchFrom(input, pos)` advancing `pos`. Two
independent quadratic sources:
1. **Inside `findMatchFrom`/`findStartFrom`** (`PikeVMMatcher findStartFrom`): a per-start-position
   retry loop — `for start=fromPos..len: tryFindAt(start)`. Each attempt can scan far before
   failing. On `?`×n against URL's `[?#&]([^=&;]+)=…` branch, every position starts a match that
   greedily consumes to end then fails (no `=`) → **a single `findMatchFrom` is O(n²)** (this is why
   URL_QUESTION_RUN, which has zero matches, still blew up). The T1.2 first-char prefilter does NOT
   help here — `?` is a valid first char for the branch.
2. **`findAll` re-scan per match** — even with O(n) per find, looping `findMatchFrom` from each match
   end re-launches the automaton → O(n·matches).

## The O(n) substrate that already exists
The **T1.4 self-anchoring boolean DFA** does the unanchored search in a **single O(n) left-to-right
pass** (re-injecting the start closure each char = implicit `.*?` prefix) — this is exactly why
boolean URL `find()` is 20× re2j. It is the right substrate. Two gaps to close:
- it returns only a **boolean / scan-start**, not the match **[start,end)** region (Round-1
  finding #6) — RE2's "find match boundaries" needs the end;
- it isn't wired to the **capture** path; `findMatchFrom` (capture) uses the O(n²) per-start PikeVM.

## Candidate direction (RE2 two-pass + streaming cursor) — to be confirmed by research
1. **One-pass region scan:** extend the self-anchoring DFA to emit **all match regions [s,e)** in a
   single O(n) pass, advancing a continuing cursor across matches (not per-start, not per-match
   restart). Needs leftmost match boundary reporting + the non-overlapping advance rule
   (advance-by-one on empty match).
2. **Per-region capture:** for each emitted region, extract group spans with PikeVM **bounded to
   [s,e)** — O(region); regions are disjoint so the total is O(n).
3. **findAll/drain = (1)+(2) in one pass** → O(n) total. Fixes both quadratic sources.

This is the RE2 structure: a fast DFA pass finds match boundaries across the whole input; a separate
submatch engine extracts captures per matched span. (Research to confirm RE2's exact FindAll +
onepass/bitstate submatch split, the continuing-cursor mechanics, and leftmost-first-vs-longest
semantics for iteration.)

## Open questions for the design (post-research)
1. Leftmost-first vs leftmost-longest match **boundaries** in find-all — what do JDK and re2j do for
   the IAST tokenizers, and what must Reggie match for redaction correctness?
2. Getting the real match **end** from the self-anchoring DFA (Round-1 #6) — region-finding variant.
3. Capture per region: reuse PikeVM bounded to [s,e) (correct, simple) vs a tagged-DFA (the refuted
   Route-A complexity — avoid). The per-region PikeVM is O(region) and bypasses the per-start
   quadratic because the region start is already known.
4. Lazy quantifiers (LDAP, Oracle) remain a SEPARATE blocker (hard compile failure) — not addressed
   by the drain fix; tracked independently.
5. Empty-match / zero-width handling in the one-pass cursor (advance-by-one), non-overlapping.

## Research integration (deep-research, 2026-06-18 — high-confidence, cited)

**The diagnosis is confirmed verbatim by the literature.** Cox (regexp3): *"PCRE implements this as
a loop that tries starting at each byte … while the RE2 implementations can run all of those in
parallel"* → single linear pass vs quadratic. **Reggie's `findStartFrom` per-start loop is exactly
the PCRE quadratic anti-pattern.** The fix is a **continuing cursor** that advances one automaton
step per input char and tracks all candidate start positions in parallel (RE2 `dfa.cc`:
`s = s->next[bytemap[c]]`, never restart).

### The unanchored-search mechanism (RE2)
Prepend a `[00-FF]*` prefix loop to the program with a **priority "Mark" separator** so
later-starting (further-right) threads are lower priority — this preserves **leftmost-first**
semantics while tracking every start in one pass (RE2 `dfa.cc` `AddToQueue`). Reggie's T1.4
self-anchoring closure (re-inject the start set each char) is the *same idea* — already proven O(n)
for boolean find.

### Two research-backed O(n) architectures (both fit Reggie)
- **(A) Single-pass PikeVM find-all (recommended first).** One left-to-right pass; each thread
  carries its capture (saved-pointer) set; **dedup-by-PC keeps it O(n·m)** because saved pointers
  record only *past* execution so two threads at the same PC execute identically (drop the
  lower-priority dup = leftmost-first). Emits *all* matches *with* captures in one pass; advance-by-
  one on empty match (Cox regexp2, Pike's `sam`). **Reggie already has this PikeVM with per-thread
  captures** — the change is to (i) add the unanchored prefix + Mark priority so one pass tracks all
  starts, and (ii) restructure `findAll`/`findStartFrom` into a continuing find-all loop instead of
  per-start re-invocation. Minimal new surface, reuses existing capture machinery. O(n·m); m≈30–100
  for the IAST patterns → effectively linear.
- **(B) RE2 two-pass (escalation if A isn't fast enough).** Lazy DFA (captures = no-ops) finds match
  *boundaries* — forward DFA → END, reverse DFA over the reversed program → START — then an
  *anchored capture engine over only the matched span*: OnePass (anchored, ≤1 viable alt/byte) →
  BitState (≤~32KB visited bitmap) → full PikeVM. Faster per-char (DFA) but much more surface
  (reverse DFA, boundary reconciliation, 2–3 capture engines). Reggie's T1.4 boolean DFA is the
  forward-boundary substrate; the reverse DFA + span capture are net-new.
- **(C) TDFA (Laurikari/Trofimovich) — explicitly NOT chosen.** Captures in registers, one DFA pass;
  this is the Route-A direction already refuted (deep `SubsetConstructor` rework, guard breakage,
  state-identity contract). Research confirms it's *a* valid option, but the integration cost stands.

### Resolved open questions
1. **Leftmost-first in find-all:** the Mark/lowest-priority-for-later-starts rule (A's prefix) gives
   leftmost-first directly; matches JDK/re2j(Perl-mode). (re2j defaults to leftmost-first; POSIX
   leftmost-longest is a mode — confirm which the IAST tokenizers rely on, but greedy leftmost-first
   is the safe default and what Reggie's PikeVM already produces.)
2. **Region end (Round-1 #6):** in (A) the match end is just where the accepting thread completes —
   no separate boundary pass needed. (In (B) it's the forward DFA accept position.)
3. **Per-region capture:** subsumed — (A) produces captures inline; no separate engine.
4. **Empty/zero-width + non-overlapping:** standard advance-by-one after an empty match; the
   continuing pass + Mark priority gives non-overlapping leftmost matches.

### Recommendation
**Pursue (A): a single-pass continuing-cursor PikeVM find-all with the unanchored prefix.** It is the
minimal, lowest-risk fix — reuses Reggie's existing per-thread-capture PikeVM, kills *both* O(n²)
sources, is directly grounded in Cox regexp2/3, and avoids the refuted TDFA and the heavier RE2
two-pass. Escalate to (B) only if benchmarks show the DFA-boundary speed is needed to beat re2j.
Lazy quantifiers (LDAP/Oracle) remain a separate, untouched blocker.

### Key citations
Cox regexp1 (Thompson NFA, lazy DFA), regexp2 (PikeVM + per-thread captures, dedup-by-PC),
regexp3 (RE2 two-pass, the PCRE-quadratic-vs-parallel anti-pattern, OnePass/BitState dispatch);
RE2 `dfa.cc` (continuing cursor, `[00-FF]*`+Mark unanchored, captures-as-no-ops), `re2.cc`
(submatch dispatch), `bitstate.cc`; Laurikari/Trofimovich TDFA (arXiv:1907.08837, re2c papers) for (C).
