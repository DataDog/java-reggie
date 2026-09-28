---
id: find-unanchored-find-prefilter
title: Unanchored-find no-match cost was reggie's fleet cap — the delivered arc (required-literal prefilter R1, single-pass unanchored scan R2, 1-char intrinsic) cut the real-corpus no-match sweep 24x (15.6ms -> 636us) and put reggie ahead of rust no-match
kind: finding
tags: [prefilter, literal-extraction, memchr, single-pass, unanchored-find, R1, R2]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/RequiredLiteralAnalyzer.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/PrefilteringMatcher.java]
source: backend-ready/hyp-unanchored-find-prefilter
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---


# Unanchored-find no-match cost was reggie's fleet cap — prefilter + single-pass scan arc delivered 24x

## Problem
Real-corpus smoke (find-rust-engine-crossover) showed reggie's fleet-mix speedup vs JDK
was capped at ~2x by the no-match scan: unanchored find() restarted matching at every
start position with no cheap rejection. Two fixes, as rust/JDK BnM do it:
- R1: memchr-style required-literal prefilter for unanchored find() — a SOUND extractor
  producing language-level facts (required substring in every match), audited against the
  JDK oracle (0 violations over the 513-pattern corpus), rejecting absent-literal inputs
  via SIMD-intrinsic'd String.indexOf before the engine runs.
- R2: single-pass unanchored scan (start-state self-loop closure, RE2 style) instead of
  per-start-position restarts for no-literal .*-prefix shapes — kills the 1.7ms-class
  patterns.

## Delivered and measured
Full arc: R1 -> R2 -> R2b/R1b -> 1-char intrinsic.
- R1 alone: no-match sweep 33.9ms -> 3.7ms (9.1x); 287/513 patterns covered (63% of
  no-match pairs instant-reject); reggie went from 10.6x behind rust to 1.17x behind,
  12x faster than JDK; matched-pair times unchanged.
- Full arc: no-match sweep 15.6ms -> 636us (24x); reggie 1.79x ahead of rust no-match at
  the R2b checkpoint; final 4-engine matrix 0.25us/pair vs rust 0.66.
- Acceptance gate: RealCorpusScanBenchmark over the committed corpus.

## Design constraints that made R1 sound
- Extractor facts must hold for EVERY match (soundness): use only exact runs, boundary
  merges between exact runs, prefix/suffix runs of class quantifiers — never
  alternation-branch guesses without LCP proof.
- The prefilter wraps the JDK fallback matcher too (allowJdkFallback mode), so no-match
  inputs keep fast rejection on every engine.
- 1-char facts need the intrinsic path (String.indexOf is SIMD-intrinsic'd) to pay.
