---
id: ev-corpus-refuse-census
type: evidence
status: confirmed
depends_on: [find-fallback-optin-refuse]
supersedes: []
related: [q-prod-readiness]
tags: [census, refuse-rate, routing, corpus, re2, pcre, iast, service-patterns, compile-time]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Default-options refuse-rate + routing census (industry corpora + IAST service patterns)

## Setup
CensusProbe (single JVM, default `Reggie.compile` — NO ALLOW_JDK_FALLBACK) against a5ef0fb
jars, Mac Corretto 26.0.1. Two corpora: (a) the 5 integration-test industry files (RE2
basic/capturing-groups, PCRE2 capturing-groups/patterns/replacements; unique first-field
patterns), (b) the 16 static-final pattern constants extracted from
IastRegexpBenchmark/IastTokenizerDrainBenchmark (the service-shaped set: SQL tokenizers
ANSI/MySQL/PostgreSQL, QUERY_OBFUSCATOR (838 chars), URL/LDAP/COMMAND redaction patterns).
Timing runs concurrently with the JMH background bench — treat compile-ms as indicative;
routing/refusal outcomes are exact. Probe: /tmp/census/{CensusProbe.java,patterns.tsv,
iast2.tsv,census.out,iast2.out}.

## Results — industry corpora (361 unique patterns)
| corpus | ok | refused | syntax-rejected |
|---|---|---|---|
| re2-basic | 49 | 0 | 0 |
| re2-groups | 54 | 2 | 0 |
| pcre-groups | 159 | 33 | 15 |
| pcre-patterns | 30 | 1 | 0 |
| pcre-replacements | 18 | 0 | 0 |
| TOTAL | 310 (85.9%) | 36+15 | 15 |

Refusal taxonomy (UnsupportedPatternException reasons): context-free recursion/subroutines 9
(not expressible in RE2J — irrelevant for RE2J replacement, JDK-only class); lazy-quantifier
shapes 8 ("shortest-match semantics not supported by this strategy"); alternation priority
conflict (DFA longest vs NFA first-alternative) 8; nullable-content capture + nullable outer
quantifier 5 (honest divergence guard: "PIKEVM_CAPTURE diverges; TDFA P..."); lookahead in
quantified group 2 + in alternation branch 1; anchor-diluted-in-DFA 2; (?(DEFINE)/(?1) 1.
The 15 "syntax-rejected" are java.util.regex.PatternSyntaxException on PCRE2's intentional
error tests (leading `*`, dup group names) — CORRECT rejects, JDK-parity behavior.

## Results — IAST service-shaped set (16 unique patterns)
13 OK — including all three SQL dialect tokenizers, the 838-char QUERY_OBFUSCATOR, COMMAND,
LDAP_JDK (lazy `.*?` now routes to PIKEVM_CAPTURE — the "previously excluded" comment in the
benchmark is stale), BARE_WORD_BOUNDARY. 3 refused — URL_RE2J ×2 + LDAP_RE2J, ALL with the
same single cause: `(?P<NAME>...)` Python/RE2 named-group syntax ("Unsupported special
group construct"). Their JDK-flavor twins (identical semantics, `(?<NAME>...)`) compile
natively — the refusal is pure syntax flavor, cured by a mechanical `(?P<` → `(?<`
normalization (RE2J has no such syntax; JDK has no `(?P`; reggie follows JDK syntax).

## Compile cost (default deadline 10s)
Industry: median 522µs, p90 1.5ms, max 72.6ms. IAST: median 3.2ms, p90 37ms, max 68.8ms.
Worst observed = 145x inside the deadline envelope. No pattern approached refusal-by-time.

## Readiness interpretation
- Service-shaped refuse-rate: 0% capability refusals; 18.75% syntax-flavor refusals with a
  mechanical normalization cure (matters for RE2J-replacement migration: patterns written
  FOR re2j need a rewrite; JDK-ecosystem patterns don't).
- Industry-corpus refusals concentrate in (i) features RE2J cannot express anyway (9/36
  engine refusals) and (ii) honest divergence guards where reggie refuses rather than
  silently deviate from JDK semantics (lazy shapes, alternation priority, nullable
  captures) — consistent with find-fallback-optin-refuse's refuse-by-default posture.
- Caveats: industry suites ≠ the literal backend-service fleet corpus (fleet dump not
  available from this machine); PCRE2 suite deliberately includes advanced/error cases;
  lazy support is shape-dependent (LDAP_JDK's `.*?` OK via PikeVM, 8 PCRE lazy shapes
  refused) — per-pattern verification needed if a service relies on lazy quantifiers.
