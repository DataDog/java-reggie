---
id: ev-refused-triage
type: evidence
status: confirmed
depends_on: [ev-backend-usage-survey, q-2pct-fallback-policy]
supersedes: []
related: [q-prod-readiness, find-bounded-quantifier-regression]
tags: [triage, refused-patterns, rewrite, jdk-retained, migration, per-pattern, rewrites-verified]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Per-pattern triage of the 11 refused real-backend patterns (2026-09-17)

Method: located each refused literal in its source file (usage context); tested mechanical
rewrites with default Reggie.compile (a5ef0fb) + boolean-parity vs JDK on samples
(/tmp/census/{RewriteProbe,SplitProbe}.java). Position-level verification of zero-width
patterns is blocked by the public find(String)-only API — noted per case.

## A. MIGRATE WITH REWRITE (4) — rewrite compiles natively, parity on samples
| pattern (site) | rewrite | status |
|---|---|---|
| `(^|\S)@[]/]` — ReactNativeParser (stacktrace) | `^@[]/]|\S@[]/]` (start-or-non-space- preceded, mechanically equivalent factoring) | compiles (BitState); no captures — pure find |
| `^/rustc|/rustlib/` — MeaningfulStackFrame:196 | **drop regex entirely**: `startsWith("/rustc") \|\| contains("/rustlib/")` | faster than both engines |
| `.*?\{\{([^}{]*)?>.*` — TemplaterHelpers (rule engine {{var}}) | `\{\{([^}{]*)>` | compiles; group span argument identical; verify group capture in review |
| `(?<tableName>\w+)(\[org=>(\w+)(:(string\|int))?])?` — UnifiedKVConfig (trino) | non-capturing inner groups + explicit `\]`: `(?<tableName>\w+)(?:\[org=>(?<orgIdColumnName>\w+)(?::(?<orgIdColumnType>string\|int))?\])?` | STRONGEST: native NameEnrichingMatcher, all samples parity, named captures preserved |

## B. REWRITE CANDIDATE, VERIFICATION REQUIRED (1)
| `(?<!(^|[A-Z]))(?=[A-Z])\|(?<!^)(?=[A-Z][a-z])` — SortTags (outliers camelCase split) | fixed-width lookbehind form: `(?<=[^A-Z])(?=[A-Z])\|(?<=.)(?=[A-Z][a-z])` — derivably equivalent ("not preceded by start-or-upper" ≡ "preceded by non-upper"; "not at start" ≡ "preceded by any") | compiles natively; position-parity NOT verifiable via public API (probe bug: find(String) is existential, not positional) — verify via SortTags unit tests |

## C. JDK-RETAINED, AUDITED (6) — explicit static Pattern + marker comment
| pattern (site) | reason |
|---|---|
| `\s*((?<kind>...):( \|$))?(?<message>.+)?` — ReactNativeParser:19 | fully-optional semantics + anchor-in-alternation; any rewrite changes behavior |
| `^(?:(?:\d+ )?[\w:-]+(?:\n\|$))+$` — multi-line frame list | anchor inside quantifier, structural |
| `(?<pre>...a\|b\|rc\|alpha...)...(?<post>...))` — SemVersion (appsec) | alternation-priority in version parsing |
| `\s*(?<pkgname>...)\[org=>...requirement...` — PyPIParsers (crawler) | alternation-priority in version-constraint parsing |
| `...invariant=\1...` ×2 — ReactMinifiedErrorMapper | backref \1 is semantic intent (validates URL number == error number); reggie gap (capture-ambiguous) |
| `(?:^\|\s+)(-?)(@?[\w.-]+):(.*?)(?=\s+-?@?[\w.-]+:\|$)` — profiling-backend | lazy + lookahead combination |

## Mechanism for C
Static java.util.regex.Pattern literals retained in code with a `// JDK-retained: <reason>`
marker linking this node; review policy from q-2pct-fallback-policy: no ALLOW_JDK_FALLBACK,
statics-only, dynamic patterns refuse into existing skip/400 handlers.

## Post-triage coverage
552/563 compiled natively (98.0%) + 4 verified rewrites + 1 candidate → effective native
coverage 98.9-99.1%; 6/563 (1.1%) JDK-retained; plus the semver {0,256} family (compiles,
slow — find-bounded-quantifier-regression) also JDK-retained.

## META-OBSERVATION
"Version-string parsing" is the recurring theme: semver bounded-quantifier slow compile
(logs-backend), SemVersion prerelease refusal (appsec), PyPI version-constraints (crawler).
If fleet-scale migration wants it, a shared version-parsing capability (reggie counter-based
x{n,m} or a shared util) would retire the whole family at once.
