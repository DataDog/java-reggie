---
id: find-backend-prod-regex-cost
title: Backend regex CPU is concentrated in logs-processing grok-on-JDK matching; savings model = cost x share x (1-1/f) with every 1% of logs-processing CPU in JDK regex worth ~$50-60K/yr
kind: finding
tags: [prod-measurement, continuous-profiler, cloud-cost, savings-estimate, logs-processing, grok]
applies_to: []
source: backend-ready/find-backend-prod-regex-cost
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---


# Backend regex CPU is concentrated in logs-processing grok-on-JDK matching

## Measured distribution (prod, continuous profiles + CCM)
- logs-processing: java.util.regex self = 5.60% of service CPU, essentially ALL match-time
  (compile 0.003%); driver ~100% grok (fsmatic GrokModule under Matcher entries); +0.55%
  InterruptibleCharSequence.charAt input plumbing. com.google.re2j = 0.00% — a RE2J
  migration had NOT landed in prod despite expectations.
- prof-analyzer: java.util.regex = 1.00% (JFR/pprof parsing).
- apm-processing: java.util.regex = 0.38% only — heavy regex there is RUST dd_sds/
  regex-automata (~11%), NOT replaceable by a JDK-semantics engine.
- Continuous profiles are not reachable via the Datadog MCP toolset; pulled via the
  dodo-cli flamegraph endpoint (env:prod, family java, cpu-time).

## Savings model
cost x share x (1 - 1/f), where f = reggie-vs-JDK speedup on the fleet mix. Initial
f=3–6x (repo benchmark corpus) overstated it; the real-corpus smoke (see
find-rust-engine-crossover) revised the honest central f to ~2x. Rule of thumb: every 1%
of logs-processing CPU in JDK regex = ~$50–60K/yr. Savings materialize only if HPA scales
replicas down (fleet ~50% of requests, diurnal autoscaling visible).

Full write-up: Datadog notebook 15576172; repo doc/investigations/multi-64k/evidence/
ev-reggie-cpu-savings-estimate.md.
