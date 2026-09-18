---
id: dead-env-gotchas
type: deadend
status: refuted
depends_on: []
supersedes: []
related: []
tags: [environment, git, gradle, classpath]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Environment gotchas hit (resolved — don't re-debug)

- Both consumer repos had stale `.git/index.lock` from crashed git processes →
  pull failed with "an editor opened by git commit" message; fix: `rm .git/index.lock`.
- logs-backend remote.origin.fetch includes deleted `green` refspec → plain
  fetch/pull dies with "couldn't find remote ref refs/heads/green"; workaround:
  `git fetch origin prod && git merge --ff-only FETCH_HEAD`.
- logs-backend filesystem scans over 29k java files time out; ALWAYS use
  `git ls-files '*.java' | xargs grep …` instead of find/grep over the tree.
- reggie-runtime jar does NOT bundle ASM (declared `implementation`): any
  standalone harness needs asm/asm-commons/asm-util 9.10.1 on the classpath
  (from ~/.gradle/caches), else every compile fails with
  NoClassDefFoundError MethodTooLargeException (misleading).
- Harness must emit results incrementally + per-entry timing; buffered output
  + one hung pattern (semver) loses 15 min of work.

Lessons from the 2026-09-16 fix sequence (don't repeat):
- STALE-JAR TRAP (struck TWICE — also for :reggie-runtime:jar alone): requesting only the
  runtime jar after editing reggie-codegen sources can be an up-to-date NO-OP (648ms BUILD
  SUCCESSFUL) — the fat runtime jar embeds the codegen classes and did not repackage. After
  codegen changes run :reggie-codegen:compileJava explicitly (or touch the codegen source),
  and sanity-check via a changed generated-class hash. Original form: `./gradlew jar ... | grep BUILD` in an && chain does NOT
  stop execution on BUILD FAILED — grep exits 0 on match, and the following
  test/verify runs against the STALE jar, producing misleading results (bit
  twice during the fix sequence). Check exit codes explicitly; never
  pipe-build-then-run in one chain.
- Gradle test tasks are cached: `./gradlew test` returning BUILD SUCCESSFUL
  in <1s means UP-TO-DATE, not re-run. For real verification use
  `./gradlew cleanTest test`.
- git commit signing via ~/.ssh/datadog_git_commit_signing can fail
  ("agent refused operation") until the key passphrase is cached; retry after
  unlocking (user unlocked on demand 2026-09-16).
- Writing regression tests: do NOT hand-derive expectations (three separate
  test-assertion bugs from misread semantics); write JDK-differential tests
  (compute expected values from java.util.regex at runtime).
- JDK Matcher is STATEFUL: matcher.matches() then matcher.find() continues
  from the end of the previous match — use a fresh Matcher per operation in
  differential tests (ReggieMatcher is stateless per call).
- jstack sampling root-causes compile hangs; run the victim via nohup+disown
  in one tool call, sample in the next (the tool kills the process group
  when the command ends).
