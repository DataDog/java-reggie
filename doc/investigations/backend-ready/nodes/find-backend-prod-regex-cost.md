---
id: find-backend-prod-regex-cost
type: finding
status: confirmed
depends_on: []
supersedes: []
related: [find-rust-engine-crossover, ev-rust-bench-lane-standardized]
tags: [prod-measurement, continuous-profiler, cloud-cost, savings-estimate, logs-processing, prof-analyzer, apm-processing, grok]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Measured prod regex CPU & $ savings for the backend services — CONFIRMED

## Reasoning chain
User asked for a $ savings estimate from cloud deployments + continuous profiles. Service
naming (user-corrected): profiling-backend = prof-analyzer; logs-backend = logs-processing
(+maybe apm-processing). Continuous profiles are NOT reachable via the Datadog MCP toolset —
pulled via the dodo-cli flamegraph endpoint (POST app.datadoghq.com/api/ui/ide/profiles/
flamegraph, OAuth from dodo-cli auth, 2h window, env:prod, family java, cpu-time; dodo-cli
fetcher cmd/profshare used + REMOVED per user directive — never commit one-off fetchers).

Measured (2026-09-17):
- logs-processing: $20,227/day ($7.38M/yr, 29d CCM all.cost service-allocated); 32,790 cores
  used / 64,963 requested. java.util.regex self = 5.60% of CPU (~1,836 cores); compile 0.003%
  (all match-time); com.google.re2j = 0.00% -> grok RE2J migration NOT landed in prod; driver
  ~100% grok (11.47 of 11.95% cumulative under Matcher entries; fsmatic GrokModule);
  +0.55% InterruptibleCharSequence.charAt regex-input plumbing.
- prof-analyzer: $2,977/day ($1.09M/yr); 5,783 cores. java.util.regex = 1.00% (JFR/pprof
  parsing).
- apm-processing: $14,902/day; java.util.regex = 0.38% only — heavy regex there is RUST
  dd_sds/regex-automata (~11%), NOT reggie-replaceable.
- Effective cost $0.021-0.026/used-core-hr across services (CCM consistent with CPU).

Savings model: cost x share x (1-1/f). Initial f=3-6x (repo 3-engine bench corpus) gave
$296-370K/yr (~89% logs-processing). REVISED after real-corpus smoke (see
find-rust-engine-crossover): honest central f ~= 2x -> logs-processing ~$210K/yr central,
range ~$207-280K/yr. Rule of thumb: every 1% of logs-processing CPU in JDK regex =
$49-62K/yr. Savings materialize only if HPA scales replicas down (fleet ~50% of requests,
diurnal autoscaling visible).

Full write-up: Datadog notebook 15576172 (updated with smoke + revision cells). Repo cairn:
doc/investigations/multi-64k/evidence/ev-reggie-cpu-savings-estimate.md (commits 9df5f78,
af1278e).
