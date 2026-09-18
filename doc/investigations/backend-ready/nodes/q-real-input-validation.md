---
id: q-real-input-validation
type: question
status: partial
depends_on: [find-rust-engine-crossover]
supersedes: []
related: [hyp-unanchored-find-prefilter]
tags: [inputs, synthetic, grok, shadow-seam, validation]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Are synthetic log lines representative of real grok traffic? — OPEN

## Reasoning chain
All real-corpus measurements (smoke + RealCorpusScanBenchmark) run 6 synthetic-but-realistic
lines; ratios per mode are the signal, absolutes indicative. Before quoting a fleet f (or a
$ number beyond the ~2x floor), validate against REAL grok traffic via the existing shadow
infra (logs.processing.grok.shadow.*, insertion point ReggieRegexPatternSupplier seam).
Also open: exact grok pattern corpus (the 513 census literals approximate the fleet mix but
grok-generated rules are the measured prod driver).

## Partial resolution (2026-09-17, 32a9e26)
LOCAL half complete: ev-real-input-parity — 527 real lines harvested from logs-backend
grok test fixtures, 270k real-pattern x real-line pairs under the drop-in contract, THREE
real divergences found and fixed (nullable-tail group span, VARIABLE_CAPTURE_BACKREF
suffix/end-anchor, parse-refusal fallback bypass), now ZERO divergences + battery committed
as RealInputParityTest. REMAINING: real PROD traffic via the shadow rollout — supplier
drafted (doc/temp/ReggieRegexPatternSupplier.java), needs reggie 0.4.0 published + a
logs-backend PR (Bastien Lemale's branch is the wiring precedent) + shadow_ratio>0.
