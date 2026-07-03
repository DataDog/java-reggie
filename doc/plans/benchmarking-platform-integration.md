# Benchmarking Platform integration plan

Date: 2026-07-03

## Goal

Wire `reggie-benchmark`'s existing JMH suite into Datadog's internal
Benchmarking Platform (BP), so that reggie gets the same regression-tracking
and history/dashboard support other Datadog Java projects have, starting with
a manually-triggered experiment before committing to per-PR automation.

## Background

- BP (`#apm-benchmarking-platform`, Confluence space `APMINT`) runs on
  Datadog's internal GitLab (`gitlab.ddbuild.io`). java-reggie is already
  mirrored to GitLab automatically and already has a working root
  `.gitlab-ci.yml` (build/publish/signing stages, `java-reggie` K8s service
  account already provisioned) — no CloudOps/repo-onboarding step is needed
  for the build/publish jobs themselves.
- `reggie-benchmark` already has 511 JMH benchmarks across ~30 classes and a
  hand-rolled `jmh` Gradle task that emits `-rf json` output at
  `build/reports/jmh/results.json`. `benchmark_analyzer`'s native `JMH`
  converter (`DataDog/relenv-benchmark-analyzer:src/src/converter/jmh.py`)
  consumes exactly this JMH JSON format directly — no custom CBMF conversion
  code is needed.
- Two integration patterns exist in the wild:
  1. **Legacy branch-per-project pattern**: a dedicated branch (e.g.
     `dd-trace-go`, `java-profiler`) of the separate `DataDog/benchmarking-platform`
     GitHub repo holds the project's `.gitlab-ci.yml`/`bp-runner.yml`/
     Dockerfile. The benchmark job (`tags: ["runner:apm-k8s-tweaked-metal"]`)
     runs entirely inside the `benchmarking-platform` GitLab project
     (`DataDog/apm-reliability/benchmarking-platform`), which is already in
     the org with runner access — the target project's own repo doesn't need
     runner access at all. It's triggered either manually from the BP
     project's own GitLab UI, or via a "bridge" trigger job the target
     project adds to its own `.gitlab-ci.yml` (`trigger: {project, branch}`),
     confirmed against `java-profiler`'s actual `.gitlab/benchmarks/.gitlab-ci.yml`.
  2. **Modern self-contained `bp-runner` pattern** (used by dd-trace-go's
     `.gitlab/benchmarks/micro/`): the same `bp-runner.yml`/Dockerfile/CI-job
     content lives inside the target project's own repo instead, and that
     repo's own runner needs `apm-k8s-tweaked-metal` access directly.
  Both use the same underlying tool (`bp-runner` + `benchmark_analyzer` from
  `benchmarking-platform-tools`) — the only difference is *where* the runner
  access lives. **We're using pattern 1**: it was initially assumed pattern 2
  was better (self-contained, versioned in this repo), but pattern 2 requires
  java-reggie's own GitHub repo to be moved into BP's runner-access org via a
  CloudOps PR (days-to-weeks lead time). Pattern 1 avoids that entirely by
  running on BP's already-provisioned project/runners.

## Scope decision

Start with a **manually-triggered** benchmarks job (`when: manual`)
rather than automatic per-PR benchmarking, to validate the pipeline
end-to-end (image build, JMH invocation, JMH→CBMF analysis via
`benchmark_analyzer`) before adding automatic triggers and a regression gate.

## Plan

### `java-reggie` branch of `DataDog/benchmarking-platform` (pushed)

Branched off `origin/main`, replacing the base template's placeholder
`steps/*.sh` scripts with a `bp-runner`-driven setup (matching the newer
style used by `java-profiler`, rather than the older hand-rolled-shell-script
style still on `main`):

- `container/Dockerfile` — based on
  `registry.ddbuild.io/images/benchmarking-platform-tools-ubuntu:latest`, JDK
  21 layered in (matching java-reggie's own build), plus
  `bp-install bp-runner benchmark-analyzer github-tools`.
- `bp-runner.yml` — clones `DataDog/java-reggie` at `$BRANCH`/`$COMMIT_SHA`,
  runs `./gradlew :reggie-benchmark:jmh`, analyzes with `framework: JMH`,
  uploads to S3 and the BP API (`project: java-reggie`).
- `.gitlab-ci.yml` — includes `benchmarking-platform-tools`'s
  `build-ci-images.template.yml` (manual `build-benchmark-ci-images` job,
  image tag `registry.ddbuild.io/ci/benchmarking-platform:java-reggie`), plus
  a `benchmarks` job (`tags: ["runner:apm-k8s-tweaked-metal"]`) that runs
  automatically when triggered by an upstream pipeline
  (`$CI_PIPELINE_SOURCE == "pipeline"`), manual otherwise.

### java-reggie repo changes

- Removed `.gitlab/benchmarks/micro/` (the pattern-2 self-contained files —
  no longer needed).
- Root `.gitlab-ci.yml`: added a `benchmarks` stage and a
  `benchmarks-trigger` bridge job (`when: manual` for now) that triggers
  `DataDog/apm-reliability/benchmarking-platform` branch `java-reggie`,
  forwarding `UPSTREAM_PROJECT_NAME`/`UPSTREAM_BRANCH`/`UPSTREAM_COMMIT_SHA`.

### Sequencing to actually run this

1. Merge the root `.gitlab-ci.yml` change to java-reggie.
2. In the `benchmarking-platform` GitLab project, manually trigger
   `build-benchmark-ci-images` once (on the `java-reggie` branch) to publish
   the image.
3. Manually run `benchmarks-trigger` from java-reggie's pipeline (or trigger
   the `benchmarks` job directly from the BP project's UI); inspect artifacts
   / BP UI (`benchmarking.us1.prod.dog`) for results. If `upload_to_bp_api`
   fails because the project isn't registered, chase down registration at
   that point rather than pre-emptively.

### Known unknowns

- Whether `java-reggie` needs to be registered with the BP API
  (`upload_to_bp_api` / `project: java-reggie`) before first use, or whether
  it's auto-created — untested; plan is to just try it and see.
- GitHub token scope for `github-tools`/PR-commenter, needed only once
  automatic PR commenting is added later.

## Out of scope for this pass

- Automatic per-PR triggering (`rules:` instead of `when: manual` on the
  bridge job).
- `fail_on_regression` / PR performance gate (`pr-gate.thresholds.yml`
  equivalent).
- Curating a specific fast "CI regression suite" subset of the 511
  benchmarks — the first experiment uses a `BENCHMARK_FILTER` CI variable
  (default matches everything) with reduced JMH iteration counts
  (`-wi 1 -i 3 -f 1`) to keep the manual run reasonably fast; proper curation
  is future work.
