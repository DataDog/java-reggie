---
id: ev-merge-hang-threaddump
type: evidence
status: confirmed
depends_on: []
supersedes: []
related: [find-sourceinterpreter-pathologies]
tags: [performance, thread-dump, analyzer-merge]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Thread dump: Analyzer.merge hotspot (SourceInterpreter quadratic growth)

Test `splitsWithForwardCrossingBranches` (6,001 if/else iterations ≈ 78k instruction nodes)
hung >120s (first run: whole-suite 600s timeout). jstack of the Gradle test worker (both
observed workers identical):

```
at org.objectweb.asm.tree.analysis.Analyzer.merge(Analyzer.java:642)
at org.objectweb.asm.tree.analysis.Analyzer.analyze(Analyzer.java:249)
at com.datadoghq.reggie.codegen.codegen.MethodSplitter.analyze(MethodSplitter.java:305)
at com.datadoghq.reggie.codegen.codegen.MethodSplitter.split(MethodSplitter.java:243)
at com.datadoghq.reggie.codegen.codegen.SplittingClassVisitor$BufferedMethod.flush(...)
```

After replacing SourceInterpreter with DescriptorInterpreter, the same test completes in
seconds (and passes once the test-formula bug was fixed — see ev-split-class-javap).
