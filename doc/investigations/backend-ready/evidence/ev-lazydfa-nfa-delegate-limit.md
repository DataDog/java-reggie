---
id: ev-lazydfa-nfa-delegate-limit
type: finding
status: confirmed
depends_on: [q-lazydfa-findfrom-leftmost]
supersedes: []
related: []
tags: [lazydfa, fallback, method-size, coverage]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# LAZY_DFA strategy unreachable for NFA >= ~6800 states — confirmed

## Reasoning chain
While engineering a leftmost-trap test pattern: the analyzer recommends LAZY_DFA
(stateCount > 300-limits, table estimate > 1MB) but RuntimeCompiler's LAZY_DFA
generation reuses NFABytecodeGenerator for findLongestMatchEnd/findMatchFrom/
findBoundsFrom — that method exceeds the JVM 64KB per-method limit around ~6800 NFA
states -> MethodTooLargeException -> JAVA_FALLBACK to java.util.regex with a warning.
Window math: DFA_TABLE eligibility (stateSlots x classCount x 4B <= 1MB, classCount =
distinct target-vector classes) usually wins first; when it doesn't, the NFA delegate
blows up. Net: LAZY_DFA generation effectively unreachable for exactly the large-NFA
patterns it targets. No corpus pattern routes LAZY_DFA (0 impact today). Fix options if
ever needed: skip the NFA-delegate span methods for huge NFA (delegate to LazyDFA
findEnd or a chunked generator). Not a work item until something routes there.
