---
id: ev-asm-semantics-probes
type: evidence
status: confirmed
depends_on: []
supersedes: []
related: [find-asm-toobytearray-sizecheck, find-sourceinterpreter-pathologies]
tags: [asm, empirical, probe-output]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Scratch probes pinning ASM 9.10.1 semantics

## WhereProbe — where does the size check fire?
ClassWriter(0), method with 25,000 × {NOP; ICONST_1; POP} (75,001 code bytes):

```
visitMaxs: OK (no throw)
visitEnd: OK (no throw)
toByteArray: THREW size=75001
```

`javap -c MethodWriter` shows the throw site in `computeMethodInfoSize`, called from
`ClassWriter.toByteArray`. COMPUTE_FRAMES|COMPUTE_MAXS writers behave identically (seen in
test failures at `realWriter.toByteArray()`).

## SourceProbe — copyOperation masks producers
MethodNode: `ldc "abc"; astore 1; iconst_5; istore 2; aload 1; invokevirtual String.length; istore 3; …`
analyzed with `Analyzer(new SourceInterpreter())`; frame at the ALOAD:

```
local 1: [VarInsnNode#58]   // ASTORE — masks the LDC "abc"
local 2: [VarInsnNode#54]   // ISTORE — masks ICONST_5
```

Both probes were run against the actual gradle-cached asm-9.10.1 / asm-tree-9.10.1 /
asm-analysis-9.10.1 jars.
