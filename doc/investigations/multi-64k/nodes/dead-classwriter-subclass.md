---
id: dead-classwriter-subclass
type: deadend
status: refuted
depends_on: [find-asm-classwriter-final]
supersedes: []
related: []
tags: [asm, classwriter, final, refuted-approach]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# SplittingClassWriter (ClassWriter subclass) — REFUTED

## Reasoning chain
First design: `SplittingClassWriter extends ClassWriter`, override `visitMethod` to return a
buffering MethodVisitor. Died on compilation: ASM 9.10 declares `ClassWriter.visit` and
`visitMethod` `public final`. No interception is possible via subclassing.

Secondary cleanup once the visitor pivot happened: the `rawVisitMethod`/`MethodVisitorFactory`
dance (needed to bypass the override while emitting chunks) became unnecessary — chunks are
emitted directly on the real writer; the splitter takes `(node, realWriter, chunk0Visitor)`.

## What replaced it
- find-asm-classwriter-final — wrapping `SplittingClassVisitor extends ClassVisitor`

## Why it matters
Any future ASM-pipeline work in this repo should start from a ClassVisitor wrapper, not a
ClassWriter subclass.
