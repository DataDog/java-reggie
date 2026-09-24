---
id: find-logs-backend-re2j-migration
type: finding
status: confirmed
depends_on: [ev-backend-usage-survey]
supersedes: []
related: [q-prod-readiness, find-fallback-optin-refuse]
tags: [logs-backend, re2j, migration, grok, insertion-point, shadow-testing, supplier, engine-abstraction]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# logs-backend grok regex is mid-migration to RE2-J — the reggie insertion point already exists

## Reasoning chain
The grok-parsing regex layer (processing-parsing, com.fsmatic.shared.parse.grok.regex) is a
deliberate engine-abstraction: RegexPatternSupplier interface + a startup-registered global
factory (RegexPatternSuppliers.getDefault, used by SafeGrokPattern/GrokPatternBundle) + 13
supplier implementations: Jdk, Re2j, Re2jWithJdkFallback, Caching, LiteralPrefiltered,
Ipv4Rewritten, Shadow (+ RegexMatcherShadowActor). Migration tooling is ACTIVE:
- ShadowRegexPatternSupplier: main + shadow supplier, configurable shadow ratio + parser
  timeout, shadow matches delegated to an actor — offline parity evaluation infra.
- InspectGrokParserMatchRulesWithRE2Job (event-jobs-logs): loads ALL track pipelines from
  Mongo and tries every grok matchRule against RE2-J, emitting success/failed count metrics
  — a fleet-wide compatibility census, already run, metrics already flowing.

Implications:
1. **Reggie drops into an existing seam**: a ReggieRegexPatternSupplier behind the existing
   factory needs zero service redesign; the shadow infra + inspection-job pattern can be
   reused verbatim for reggie parity validation (swap the shadow supplier, add a
   ReggieRegistry-style job, compare event_jobs_logs.inspect_re2j.* baselines).
2. **The migration's hard part is already reggie's strength**: the RE2J migration stalls on
   RE2-incompatible patterns (lookaround/backrefs — RE2J cannot run them). Re2jWithJdk-
   FallbackRegexPatternSupplier exists for exactly that: RE2J-first, then a WARN-logged
   SILENT fallback to backtracking java.util.regex. Reggie handles the incompatible syntax
   natively and linearly (PikeVM/BitState routes) — no backtracking fallback needed — and
   refuses-by-default where it cannot guarantee semantics (see find-fallback-optin-refuse).
   NOTE: Re2jWithJdkFallback is currently defined-but-unreferenced in main code; which
   supplier production actually registers was not found in-repo (benchmarks register Jdk;
   production registration likely in a service bootstrap not present in these trees) —
   confirm with logs-backend owners before any migration plan.
3. profiling-backend's UserDefinedRegex (RE2J for user-supplied request regexes, ReDoS
   immunity) is the second insertion point — direct swap of the engine class, same 512-char
   cap and 400-mapping, gaining lookaround/backref support with linear-time guarantees.

## Open questions
- Which supplier is registered in production today (Jdk vs Re2j vs Re2jWithJdkFallback)?
- What did the InspectGrokParserMatchRulesWithRE2Job census report (success/fail counts —
  i.e., the % of fleet grok patterns RE2J cannot even compile — reggie's ceiling there)?
