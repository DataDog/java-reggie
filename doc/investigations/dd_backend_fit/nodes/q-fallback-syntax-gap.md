---
id: q-fallback-syntax-gap
type: question
status: open
depends_on: [find-brace-literal-rejected]
supersedes: []
related: []
tags: [fallback, design, syntax, parser]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**, reggie-runtime/src/main/java/com/datadoghq/reggie/Reggie.java]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Why doesn't compileAllowingFallback rescue syntax-level rejections?

`compileAllowingFallback` rescues UnsupportedPatternException (semantic
rejections) but NOT reggie's own PatternSyntaxException — yet JDK accepts those
same patterns (literal `}`). Two open design questions for reggie:
(1) should the fallback path defer to JDK parsing when reggie's parser rejects
syntax JDK accepts? (2) or is the right fix parser leniency (literal `}`)?
Either unblocks the 13 brace patterns without consumer rewrites; today there
is NO migration path for them.
