# Reggie feature requirements — grok log-parsing adoption

Context: We're evaluating Reggie 0.3.0 as a drop-in replacement for java.util.regex in the grok log-parsing pipeline (logs-processing service, ~16 µs/op on an access-log pattern). Benchmark: GrokModuleBenchmark.parse, baseline 16.4 µs/op (JDK).

---
## P0 — Compile blockers (throw today)

1. Atomic groups (?>...)

- Syntax: (?>X) — possessive non-capturing group
- Error: UnsupportedPatternException: Unsupported special group construct thrown from RegexParser.parseGroup() line 291 — no case '>' in the special-group dispatch
- Frequency: 5 core grok patterns (numberStr, numberExtStr, quotedString, unixPath, winPath); also emitted directly in match-rule bodies for optional fields ((?>%{_method} |), (?>HTTP\/...|)). Every grok job using %{number}, %{notSpace}, or path patterns hits this
- Semantics for a linear-time engine: (?>X) is purely a backtracking-prevention hint. A DFA/NFA with no backtracking can accept (?>X) and treat it as (?:X) with identical semantics — the fix is to add case '>': // atomic group, treat as non-capturing in parseGroup() and continue parsing as a standard non-capturing group

---
2. \Q...\E literal quoting

- Syntax: \Qliteral text\E — quotes all metacharacters between \Q and \E
- Bug: Silent misparsing. parseEscape() default branch (line 527) converts \Q → LiteralNode('Q') and \E → LiteralNode('E'). No exception thrown; the compiled pattern silently matches the wrong text
- Frequency: Emitted by Pattern.quote() in GrokRuleBuilders.DATE for every literal separator in a date format string — e.g. dd/MMM/yyyy:HH:mm:ss Z produces [\d]{2}\Q/\E(?:Jan|...)\Q/\E[\d]{4,19}\Q:\E.... Any grok pipeline using %{date(...)} is affected
- Risk level: Higher than P0 in some ways — it doesn't fail loudly, it produces a pattern that compiles and runs but matches different strings than intended. Correctness hazard
- Fix: Add case 'Q': return parseQuotedLiteral() in parseEscape(), consuming characters until \E and emitting them as a concatenation of LiteralNodes

---
## P1 — Performance (after syntax is fixed, Reggie is 5.3% slower than JDK)

Benchmark after stripping (?> → (?: to force 100% Reggie coverage:

┌───────────────────┬─────────────┬────────┐
│      Engine       │    Score    │ ±Error │
├───────────────────┼─────────────┼────────┤
│ JDK               │ 15.63 µs/op │ ±0.30  │
├───────────────────┼─────────────┼────────┤
│ Reggie (stripped) │ 16.45 µs/op │ ±0.38  │
└───────────────────┴─────────────┴────────┘

3. Allocation-free capture-group extraction

- Problem: ReggieMatcher.match(String) allocates a MatchResult object on every successful match. The grok pipeline calls matcher.group(i) after every match to extract captures. JDK's Matcher is a stateful object reused across calls — group boundaries are stored as a int[] and group(i) extracts substrings lazily with no intermediate allocation
- Impact: The benchmark has 3 matching inputs out of 4. Each iteration allocates 3 MatchResult objects. With ~16 µs/op and multiple fields per match, allocation pressure is visible in GC and per-iteration cost
- Ask: Add a matchInto(String input, int[] groupStarts, int[] groupEnds) method (or a reusable Matcher-style object) that stores group boundaries in caller-provided arrays without allocating a result wrapper. The hot path in SafeGrokPattern.matches() would then call groupStarts[i] / input.substring(...) directly

4. DFA state-budget / hybrid fallback for large alternation patterns

- Problem: The grok IPv4/IPv6/hostname union pattern expands to ~2000 chars with deeply nested alternatives and quantifiers. For such patterns, a pure DFA can have exponentially more states than a backtracking NFA traverses in practice. JDK's NFA explores O(input × states visited) = near-linear for typical log inputs that match on the first alternative, while Reggie's DFA may precompute state sets for all possibilities upfront
- Observed: Even after fixing the above allocation issue, the DFA for this pattern is unlikely to out-perform JDK without a state-space budget
- Ask: A BacktrackConfig-style threshold (already present in the codebase) that, when the compiled DFA exceeds N states, falls back to an NFA simulation path. Or expose a Reggie.compile(pattern, Strategy.NFA) override for patterns known to have large DFAs

---
## P2 — Nice to have

5. \Q...\E inside character classes [...]

- parseCharClass() also has no \Q handler — the same silent misparsing occurs inside [...]. Low frequency in current patterns but a logical follow-on once bare \Q...\E is implemented

6. Expose UnsupportedPatternException as a checked/public API contract

- Currently UnsupportedPatternException extends ParseException which extends RuntimeException. Callers implementing a JDK-fallback supplier (like ReggieRegexPatternSupplier above) must catch Exception broadly. A public, stable exception type would make fallback code more precise

---
Key parser locations:
- parseGroup() line 291 — (?> catch-all throw
- parseEscape() line 527 — \Q/\E silent default
- Source: reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/RegexParser.java
---

## Final adoption architecture/status

The logs-backend Grok access-log path now uses a native, deterministic Reggie route under
`CapturePolicy.NAMED_ONLY`:

```
regex AST -> PatternCategorizer -> LinearTokenSequencePlan -> LinearTokenSequenceMatcher
```

Important properties:

- The route is structural: it categorizes reusable token atoms (IP/host, non-space fields,
  quoted fields, integers, decimals, optional request fragments, delimiter captures, and trailing
  bracketed logger capture). It does **not** route by exact pattern string or `grokN` capture names.
- `CapturePolicy.NAMED_ONLY` preserves original named group indexes so Grok can continue calling
  `group(originalIndex)` after discovering names from the expanded regex.
- The old ad-hoc `AccessLogGrokMatcher` oracle has been removed; production routing now depends on
  the generic categorizer/planner/runtime matcher only.
- The two real expanded logs-backend Grok patterns are committed as runtime test resources and have
  regression tests proving they route through `LinearTokenSequenceMatcher`.
- JDK/Reggie named capture-boundary equivalence is tested for the real expanded patterns across
  common/combined access logs, optional method/version fields, `-` byte count, empty quoted fields,
  IPv6/hostname clients, and logger bracket decoys.

Integrated benchmark after scratch-state reuse and oracle removal (`-wi 2 -i 3 -f 2 -prof gc`):

| Engine | Score | Allocation |
|---|---:|---:|
| JDK regex | 16.210 ± 2.128 us/op | 7701.393 ± 154.805 B/op |
| Reggie native token sequence | 2.353 ± 0.161 us/op | 7682.979 ± 61.845 B/op |

Target coverage for the logs-backend benchmark remains:

```
[Reggie] coverage: 2/2 native, 0/2 internal JDK fallback, 0/2 after atomic-strip, 0/2 supplier JDK fallback
```
