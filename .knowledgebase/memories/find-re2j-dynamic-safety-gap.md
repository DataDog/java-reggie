---
id: find-re2j-dynamic-safety-gap
title: Every re2j site in the dd backends compiles runtime-supplied patterns, so replacing re2j requires bounded compile (work budget + deadline + state caps, default-on graceful rejection) — RE2J's program-size caps were the safety reggie lacked; now delivered
kind: finding
tags: [re2j, untrusted, dos, dynamic-patterns, blocker]
applies_to: [reggie-runtime/src/main/java/com/datadoghq/reggie/Reggie.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieNativeCompileBudget.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieCompiledPatternCompiler.java]
source: dd_backend_fit/find-re2j-dynamic-safety-gap
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# re2j sites take runtime-supplied patterns — replacement requires bounded compile (now delivered)

Every re2j call site in both repos compiles RUNTIME-SUPPLIED patterns:
- pb: `prof-viz-java/.../UserDefinedRegex.java` — request-supplied, 512-char
  cap, re2j chosen for ReDoS immunity, surfaces 400 on syntax error.
- lb (6 files): quantization rules (anchored `\A(?:…)\z`), service-resolution
  remapping, Stringer event filters, intake attachments, grok
  Re2jRegexPatternSupplier (DOTALL, non-production).

API port is trivial (wrappers/SPI), and reggie accepts a superset of RE2 syntax
(\A/\z supported on trunk, DOTALL flag supported). The original blocker: re2j guarantees
bounded compile via program-size caps, while reggie had exponential-compile patterns
(find-semver-exponential-compile) → CPU-DoS vector on request-supplied input.

RESOLVED: bounded compile landed — NFA state cap, DFA work budget + wall-clock deadline,
total-compile deadline, per-method emission budget, compile-scope OOM catch (see the
deadline/coverage memories). Any remaining dynamic-site adoption is now a consumer-side
policy question (pattern length caps exist in UserDefinedRegex), not a compile-boundedness
question.
