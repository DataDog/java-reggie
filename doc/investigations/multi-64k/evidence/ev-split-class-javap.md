---
id: ev-split-class-javap
type: evidence
status: confirmed
depends_on: []
supersedes: []
related: [find-l2-splitter-shipped]
tags: [javap, bytecode-decode, chunk-chain, test-bug]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# javap of the produced split class: chain correct, test formula was wrong

Standalone repro (BranchDump, n=6001, static `()I`, a/b int locals, if/else per iteration).
`javap -c -p` of the split class:

- `compute` (chunk 0) tail, iteration 2501 straddling the cut at its endLabel:
```
47377: ifeq 47390          // local jump to else block (same chunk)
47380-47383: a += 2
47384: iload_0; iload_1
47386: invokestatic compute$s1:(II)I   // crossing GOTO → inline call+return
47389: ireturn
47390-47393: b += 3
47394-47396: iload_0; iload_1; invokestatic compute$s1:(II)I  // chunk tail call
47399: ireturn
```
- `compute$s1` starts `sipush 2502` (exact continuation), ends analogously calling `$s2` at ~5001;
- `$s2` ends `sipush 6001 … iload_0; sipush 1000; imul; iload_1; iadd; ireturn`.

Result 6,011,000 = 2×odds×1000 + 3×evens — **the splitter output was correct; the test's
expected formula was inverted** (IFEQ jumps on k&1==0, so even k take the else/b branch).
Lesson: decode the produced bytecode (javap) before suspecting the splitter.
