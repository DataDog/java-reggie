---
id: find-re2j-dynamic-safety-gap
type: finding
status: confirmed
depends_on: [find-semver-exponential-compile, ev-harness-results]
supersedes: []
related: [find-api-surface-sufficient]
tags: [re2j, untrusted, dos, dynamic-patterns, blocker]
applies_to: [reggie-runtime/src/main/java/com/datadoghq/reggie/Reggie.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieNativeCompileBudget.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieCompiledPatternCompiler.java]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# re2j replacement blocked: all re2j sites are dynamic/untrusted; reggie lacks bounded compile

Every re2j call site in both repos compiles RUNTIME-SUPPLIED patterns:
- pb: `prof-viz-java/.../UserDefinedRegex.java` — request-supplied, 512-char
  cap, re2j chosen for ReDoS immunity, surfaces 400 on syntax error.
- lb (6 files): quantization rules (anchored `\A(?:…)\z`), service-resolution
  remapping, Stringer event filters, intake attachments, grok
  Re2jRegexPatternSupplier (DOTALL, non-production).

API port is trivial (wrappers/SPI), and reggie accepts a superset of RE2 syntax
(\A/\z supported on trunk, DOTALL flag supported). BUT re2j guarantees bounded
compile via program-size caps; reggie trunk has exponential-compile patterns
(find-semver-exponential-compile) → CPU-DoS vector on request-supplied input.
Today re2j is strictly safer for untrusted patterns. Reggie needs a
state/work-count compile budget with graceful rejection, default-on in
Reggie.compile(), before taking any dynamic site.
