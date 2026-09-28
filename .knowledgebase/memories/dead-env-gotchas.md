---
id: dead-env-gotchas
title: Build/test/harness gotchas for java-reggie and consumer-repo regex audits — stale-jar trap, gradle caching, piped-build chains, JDK-differential tests, JDK matcher statefulness (resolved — don't re-debug)
kind: dead-end
tags: [environment, git, gradle, classpath]
applies_to: []
source: dd_backend_fit/dead-env-gotchas
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# Environment gotchas hit (resolved — don't re-debug)

- Both consumer repos had stale `.git/index.lock` from crashed git processes →
  pull failed with "an editor opened by git commit" message; fix: `rm .git/index.lock`.
- Large consumer repos (e.g. logs-backend): remote may carry dead refspecs
  (plain fetch dies with "couldn't find remote ref …"; workaround
  `git fetch origin <branch> && git merge --ff-only FETCH_HEAD`) and
  filesystem-wide greps time out — ALWAYS `git ls-files '*.java' | xargs grep …`.
- reggie-runtime jar does NOT bundle ASM (declared `implementation`): any
  standalone harness needs asm/asm-commons/asm-util 9.10.1 on the classpath
  (from ~/.gradle/caches), else every compile fails with
  NoClassDefFoundError MethodTooLargeException (misleading).
- Harness must emit results incrementally + per-entry timing; buffered output
  + one hung pattern (semver) loses 15 min of work.

Reggie build discipline (don't repeat):
- STALE-JAR TRAP: requesting only the runtime jar after editing reggie-codegen sources can
  be an up-to-date NO-OP — the fat runtime jar embeds the codegen classes and did not
  repackage. After codegen changes run :reggie-codegen:compileJava explicitly and
  sanity-check via a changed generated-class hash.
- `./gradlew jar ... | grep BUILD` in an && chain does NOT stop on BUILD FAILED — grep
  exits 0 on match and the following test/verify runs against the STALE jar. Check exit
  codes explicitly; never pipe-build-then-run in one chain.
- Gradle test tasks are cached: `./gradlew test` returning BUILD SUCCESSFUL
  in <1s means UP-TO-DATE, not re-run. For real verification use
  `./gradlew cleanTest test`.
- git commit signing via ~/.ssh/datadog_git_commit_signing can fail
  ("agent refused operation") until the key passphrase is cached; retry after
  unlocking (user unlocked on demand 2026-09-16).
- Writing regression tests: do NOT hand-derive expectations (repeated
  test-assertion bugs from misread semantics); write JDK-differential tests
  (compute expected values from java.util.regex at runtime).
- JDK Matcher is STATEFUL: matcher.matches() then matcher.find() continues
  from the end of the previous match — use a fresh Matcher per operation in
  differential tests (ReggieMatcher is stateless per call).
- jstack sampling root-causes compile hangs; run the victim via nohup+disown
  in one tool call, sample in the next (the tool kills the process group
  when the command ends).
