---
id: find-asm-classwriter-final
type: finding
status: confirmed
depends_on: []
supersedes: []
related: [dead-classwriter-subclass, find-l2-splitter-shipped]
tags: [asm, classwriter, classvisitor, interception]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# ASM 9.10 ClassWriter methods are final — intercept with a wrapping ClassVisitor

## Reasoning chain
`javap` on asm-9.10.1.jar shows:
`public final void visit(int,int,String,String,String,String[])` and
`public final MethodVisitor visitMethod(int,String,String,String,String[])` in
`org.objectweb.asm.ClassWriter`. A `ClassWriter` subclass therefore cannot intercept method
emission ("overridden method is final" compile errors).

Consequence: the interception point must be a **wrapping `ClassVisitor`** (the standard ASM
instrumentation shape) delegating to the real ClassWriter, which stays the terminal pipeline
stage and `toByteArray()` producer. Reggie's generators had parameters typed `ClassWriter`, so
they were mechanically widened to `ClassVisitor` (~32 files; compiler-verified; the only
non-visitor use, `RecursiveDescentBytecodeGenerator.generate()`'s internal `cw.toByteArray()`,
was restructured to real-writer + front-visitor). Both pipeline entry points
(`RuntimeCompiler.generateBytecode`, `ReggieMatcherBytecodeGenerator`) construct
`new ClassWriter(COMPUTE_FRAMES|COMPUTE_MAXS)` wrapped by `new SplittingClassVisitor(real)`.

This pivot was the user's suggestion ("Can you just insert a ClassVisitor?") after the
subclass approach died on finality.

## Evidence
- find-asm-toobytearray-sizecheck for the other half of the ASM API reality

## Open questions
- none
