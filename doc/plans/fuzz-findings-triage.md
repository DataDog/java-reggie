# Algorithmic fuzz: divergence triage

Findings from the default seed of `AlgorithmicFuzzTest`
(seed `0xC0DEFEED_DEADBEEFL`, 500 patterns × 8 inputs). 161 raw
divergences, shrunk to 64 unique minimal repros. Each row is grouped
by what the smallest repro suggests is the underlying bug.

## A. Lazy quantifier silently treated as greedy (highest priority)

The most common category. `*?`, `+?`, `??`, `{n,m}?` produce a
greedy match in Reggie while JDK produces the lazy one.

| Pattern | Input | JDK find | Reggie find |
|---|---|---|---|
| `.??` | `b` | `[0,0)` | `[0,1)` |
| `.??` | `0` | `[0,0)` | `[0,1)` |
| `.??` | `_` | `[0,0)` | `[0,1)` |
| `(?:a)??` | `a` | `[0,0)` | `[0,1)` |
| `b??\|(){3}` | `b` | `[0,0)` | `[0,1)` |
| `\A.*?` | `a` | `[0,0)` | `[0,1)` |
| `\A.*?` | `1` | `[0,0)` | `[0,1)` |
| `[^0][0]*?` | `10` | `[0,1)` | `[0,2)` |
| `-?.{3,}?\|1{0}` | `aa0-` | `[0,0)` | `[0,3)` |

`.??` on a single char is the canonical case. This should be the
**first** category to investigate — it likely cascades into a lot of
the broader findings.

## B. Empty/zero-width match handling

| Pattern | Input | Kind |
|---|---|---|
| `b{0,3}[c]{0}` | `""` | find: jdk=true, reggie=false |
| `c{3}()\|$` | `""` | first-match span differs |
| `1?$` | `""` | first-match span differs |
| `$c?` | `""` | first-match span differs |
| `([^a]{2}\z\|){1}` | `""` | first-match span differs |
| `1{0}(c{0}\|]{4})\|-?.{3}` | `_-0` | first-match span differs |

Patterns that *can* match zero-width report different match starts
between Reggie and JDK. Often related to anchor placement or
zero-width-only alternation branches.

## C. Negated character class against single char

| Pattern | Input | Kind |
|---|---|---|
| `[^c]c{0,3}` | `b` | matches: jdk=true, reggie=false |
| `[^c]c{0,3}` | `1` | find: jdk=true, reggie=false |
| `[^c]c{0,3}` | `1_c` | span differs |

`[^c]c{0,3}` matches "b" (1 char not c, then 0 c's). Reggie says no.
Likely the same root cause as the {0,N} cases we already fixed in
STATELESS_LOOP, but in another codegen path now that the lower bound
plus zero-allowed upper bound combination changes the strategy.

## D. Self-referencing backreference in alternation

| Pattern | Input | Kind |
|---|---|---|
| `a\|(\1\1){1}` | `""` | matches differs, find differs |
| `[a]?(\1{2}){2}\|b` | `""` | find differs |
| `(.{3}a{1}_{3})?\1` | `""` | find differs |

JDK rejects these as semantically meaningless (the backref refers to
a group that hasn't matched yet), Reggie evaluates them. Already
covered by a recent PR for related cases but corner-shapes remain.

## E. Quantified zero-width anchors

| Pattern | Input | Kind |
|---|---|---|
| `\A{3,4}?(a\|[1a]+)` | (multiple) | span differs |
| `\A{3,6}\|...` | (multiple) | span differs |
| `\Z+b\|...` | (multiple) | span differs |
| `_{1}(\A)\|_` | `-_` | find differs |

Anchors quantified with `{n,m}` should still be zero-width — JDK
respects this. Reggie's NFA construction may be expanding `\A{3,4}`
to "3-4 anchor states" and getting confused by the surrounding
alternation.

## F. Anchor inside character class context / interior

| Pattern | Input | Kind |
|---|---|---|
| `\Z.[a]{1}\|_-` | `_a` | find differs |
| `]\A\|b` | `cb` | find differs |
| `\Z]{4}` (in alt) | varies | matches/find differs |

`\A` / `\Z` placed inside an alternation branch where they aren't at
the start/end of the branch. Related to the anchor-aware DFA fix in
`95f71ec` but a few cases still slip through.

## Suggested execution order

1. **A (lazy quantifiers)** — single root cause likely, big finding
   reduction. Probably in `RegexParser` quantifier handling or in
   the codegen for `*?` / `??`.
2. **C (negated char class + bounded zero)** — small targeted fix
   per the STATELESS_LOOP pattern from `3fdeee5`.
3. **B (zero-width matches)** — needs careful review of `find()`
   behaviour at empty positions in each strategy.
4. **D (self-ref backref)** — probably accept divergence (declare
   the JDK behavior the expected one) and reject these patterns at
   parse time, since they have no useful semantics.
5. **E + F (anchor placement)** — extend the anchor-aware DFA fix.

Once A is closed, re-run the fuzz to re-count. Each fix should
shrink the divergence ceiling in `AlgorithmicFuzzTest`, eventually
tightening it from 25% down to ≈0%.
