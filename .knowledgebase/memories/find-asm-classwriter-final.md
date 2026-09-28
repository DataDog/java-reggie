---
id: find-asm-classwriter-final
title: ASM interception shape — ClassWriter.visit/visitMethod are final in 9.10, so pipeline instrumentation must wrap a ClassVisitor delegating to the terminal ClassWriter
kind: finding
tags: [asm, classwriter, classvisitor, interception]
applies_to: []
source: multi-64k/find-asm-classwriter-final
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# Wrap a ClassVisitor — ClassWriter methods are final

`javap` on asm-9.10.1.jar shows:
`public final void visit(int,int,String,String,String,String[])` and
`public final MethodVisitor visitMethod(int,String,String,String,String[])` in
`org.objectweb.asm.ClassWriter`. A `ClassWriter` subclass therefore cannot intercept method
emission ("overridden method is final" compile errors).

Consequence: the interception point must be a **wrapping `ClassVisitor`** (the standard ASM
instrumentation shape) delegating to the real ClassWriter, which stays the terminal pipeline
stage and `toByteArray()` producer. In the (now dropped) splitter patch, reggie's generators
had parameters typed `ClassWriter` and were mechanically widened to `ClassVisitor`
(~32 files); the only non-visitor use, `RecursiveDescentBytecodeGenerator.generate()`'s
internal `cw.toByteArray()`, was restructured to real-writer + front-visitor. Both pipeline
entry points (`RuntimeCompiler.generateBytecode`, `ReggieMatcherBytecodeGenerator`) construct
`new ClassWriter(COMPUTE_FRAMES|COMPUTE_MAXS)` wrapped by the front visitor — that wiring was
removed when the splitter was dropped and must be re-added on revival (see the L2 memories).
