---
id: dead-classwriter-subclass
title: ASM ClassWriter.visit/visitMethod are final (9.10) — method emission cannot be intercepted via subclassing; wrap with a ClassVisitor instead
kind: dead-end
tags: [asm, classwriter, final, refuted-approach]
applies_to: []
source: multi-64k/dead-classwriter-subclass
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# ClassWriter subclassing is impossible — wrap a ClassVisitor

First design: `SplittingClassWriter extends ClassWriter`, override `visitMethod` to return a
buffering MethodVisitor. Died on compilation: ASM 9.10 declares `ClassWriter.visit` and
`visitMethod` `public final`. No interception is possible via subclassing.

The interception point must be a **wrapping `ClassVisitor`** (the standard ASM
instrumentation shape) delegating to the real ClassWriter, which stays the terminal pipeline
stage and `toByteArray()` producer. Chunks are emitted directly on the real writer; the
splitter takes `(node, realWriter, chunk0Visitor)`.

Any future ASM-pipeline work in this repo should start from a ClassVisitor wrapper, not a
ClassWriter subclass.
