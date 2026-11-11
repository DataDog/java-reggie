# Guardrails: required checks, prohibited actions, protected paths

Detail doc for `AGENTS.md`. This is the written guardrail list the hooks and CI jobs are built
from. The prose here is advisory; the scripts, hooks and workflows named below are the gates.
Update this list in the same PR that adds, removes or retunes a gate.

## Required checks

| Check | Local command | Enforced by | Healthy output |
|-------|---------------|-------------|----------------|
| Formatting | `./gradlew -q spotlessCheck` (fix: `./gradlew spotlessApply`) | `.claude/settings.json` (blocks `git push`), `.githooks/pre-push`, CI `./gradlew build` | exit 0, no output |
| Build, unit tests, meta-test, coverage | `./gradlew build` | `ci.yml` job `build`, GitLab job `build` | `BUILD SUCCESSFUL in …` |
| Fuzz divergence gate at the CI budget | `./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.divergenceGate_enforcedViaProperty' -Dreggie.fuzz.enforce=true -Dreggie.fuzz.maxFindings=28` | `ci.yml` step `Correctness gates`, GitLab job `correctness_gate` | `[divergence-gate] patterns=16280 skipped=8720 inputs-checked=260480 checks-timed-out=0 findings=15`, then `[divergence-gate] budget=28 (known pre-existing findings)` and `BUILD SUCCESSFUL` |
| Coverage gates | `./gradlew jacocoAggregateReport jacocoVerify` | `ci.yml`; Codecov patch gate (`codecov.yml`) | `BUILD SUCCESSFUL` |
| Dual-path gate | `python3 scripts/check-dual-path.py` | `.github/workflows/agent-gates.yml` | `Dual-path gate OK: 39 strategies covered on both paths` (the count follows `MatchingStrategy`) |
| Scratch-file gate | `python3 scripts/check-scratch-files.py origin/main HEAD` | `agent-gates.yml`, `.githooks/pre-push` | `Scratch-file gate OK` |
| Gate-budget ratchet | `python3 scripts/check-gate-budgets.py` (and `--self-test`) | `.github/workflows/gate-budgets.yml` | `Gate-budget check OK: fuzz budget 28, …`; a `WARN:` line names any source default that differs from the CI budget |
| Secrets, dependencies, CodeQL | CI only | `security.yml` | all jobs green |

`./gradlew build` runs `AlgorithmicFuzzTest.divergenceGate()` with the source default
`KNOWN_FINDINGS_BUDGET`, which can be looser than the CI budget. Pass
`-Dreggie.fuzz.maxFindings=28` locally, or run the CI gate command above, before pushing engine
changes.

Install the git hooks once per clone: `git config core.hooksPath .githooks`. The pre-push hook
loads the scratch-file policy from `origin/main` and runs `spotlessCheck` on the working tree.

## Prohibited actions

- Never raise a tolerance to turn a red check green: the fuzz budget (`-Dreggie.fuzz.maxFindings`,
  `KNOWN_FINDINGS_BUDGET`), the `jacocoVerify` minimums in `build.gradle`, a `@Timeout` value, or
  the `EXEMPT`/`BUDGETS` tables in `scripts/check-*.py`. Fix the code. If a raise is justified (for
  example a corpus shift), state the measured cause in the PR description and record new
  divergences in `doc/fuzz/YYYY-MM-DD.md`.
- Never make a required check non-blocking: no `continue-on-error`, no `allow_failure` on the
  `build`/`correctness_gate` jobs, no `reggie.metatest.enforce=false` default, no `@Disabled` on a
  gate test.
- Never bypass the pre-push hook with `--no-verify` without stating why in the PR description.
- Never edit `CLAUDE.md` (a redirect stub) or the cairn-managed block in `AGENTS.md`; distill
  memories into `.knowledgebase/memories/` with the cairn skill, not by hand.
- Task-scoped documents go in `doc/temp/` (see `AGENTS.md`).

## Protected paths

Changes to these paths are reviewed changes: the PR description names the path and the reason.

- `.github/workflows/`, `.gitlab-ci.yml`, `codecov.yml`
- `.githooks/`, `.claude/settings.json`
- `scripts/check-*.py`
- The `jacocoVerify` block in `build.gradle`, the `reggie.metatest.enforce` default in
  `reggie-runtime/build.gradle`, and `KNOWN_FINDINGS_BUDGET` in `AlgorithmicFuzzTest.java`

## Reporting check results

Report every check as passed, failed, skipped or not run, with the command and its last output
line. A summary verdict never replaces a failed or skipped required check. Without
`-Dreggie.fuzz.enforce=true`, `divergenceGate_enforcedViaProperty` is skipped by a JUnit
assumption, so a green test run does not show that the CI fuzz gate passed.

## Parallel sessions

Run one task per worktree (for example under `.claude/worktrees/`). Keep coupled changes, such as
the two dual-path files, in one session. After merging the results of parallel sessions, run
`./gradlew build` and the CI fuzz gate on the merged tree. Checks that passed on separate branches
do not show that their combination works.
