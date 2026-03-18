# Reggie Architecture

**Last Updated**: 2026-02-04

**A hybrid compile-time and runtime regex-to-bytecode compiler with specialized DFA generation**

## TL;DR - The Big Idea

**What Reggie does**: Takes your regex pattern (like `\d{3}-\d{3}-\d{4}` for phone numbers) and generates custom Java bytecode specifically optimized for that exact pattern.

**Why it's fast**: Instead of using a generic regex engine that interprets patterns at runtime, Reggie generates code that acts like you hand-wrote a specialized matcher for each pattern. Think of it as the difference between:
- **JDK Pattern**: "Follow these generic instructions to match any pattern" (interpreter)
- **Reggie**: "Here's custom code that only matches phone numbers" (compiled)

**How it works** (30-second version):
1. Parse pattern → Build state machine → Generate optimized bytecode
2. Different patterns get different code: simple patterns get ultra-fast straight-line code, complex patterns get more sophisticated strategies
3. Works at compile-time (zero overhead) or runtime (small first-use cost, then cached)

**Result**: 7-389x faster than JDK's Pattern, with guaranteed linear time (no catastrophic backtracking).

---

> **Note**: This document focuses on the core compilation pipeline and five fundamental strategies. The actual codebase implements 20 specialized bytecode generators for different pattern types. The principles below apply across all strategies.

## Table of Contents
1. [Overview](#overview)
2. [Hybrid Architecture](#hybrid-architecture)
3. [Architecture Diagram](#architecture-diagram)
4. [Component Details](#component-details)
5. [Bytecode Generation Strategies](#bytecode-generation-strategies)
6. [Performance Characteristics](#performance-characteristics)
7. [Pros and Cons](#pros-and-cons)
8. [Examples](#examples)

---

## Overview

Reggie is a regex library that generates specialized Java bytecode through **two complementary paths**:

1. **Compile-Time Path**: Annotation processor generates bytecode during build
2. **Runtime Path**: RuntimeCompiler generates bytecode on-demand using hidden classes (Java 21+)

Both paths share the same compilation pipeline:
1. Parse patterns to Abstract Syntax Tree (AST)
2. Construct NFA automata using Thompson's construction
3. Convert to DFA when possible using subset construction
4. Analyze pattern and select optimal bytecode generation strategy
5. Generate specialized bytecode with inline character checks

**Key Innovation**: Pattern-specific bytecode generation that eliminates interpretation overhead and runtime allocations, available both at compile-time (zero overhead) and runtime (lazy with caching).

---

## Hybrid Architecture

### Compile-Time Path (Annotation Processor)

**Use Case**: Static patterns known at build time (email validation, phone numbers, etc.)

**Flow**:
```
@RegexPattern("\\d+")
→ ReggiProcessor (annotation processor)
→ Shared compilation pipeline (reggie-codegen)
→ Generated .class file
→ ServiceLoader registration
→ Zero runtime overhead
```

**Advantages**:
- 0ms first-use latency (pre-compiled)
- Compile-time error detection
- Perfect for AOT compilation (GraalVM)
- Smaller runtime dependencies

**Limitations**:
- Requires annotation processor setup
- Cannot handle runtime-provided patterns
- Increases build time and JAR size

### Runtime Path (RuntimeCompiler)

**Use Case**: Dynamic patterns from user input, configuration files, search features

**Flow**:
```
Reggie.compile("\\d+")
→ RuntimeCompiler cache lookup
→ (if not cached) Shared compilation pipeline (reggie-codegen)
→ Hidden class definition (MethodHandles.Lookup)
→ Cache for future use
→ ~5-10ms first-use latency, <1ns subsequent calls
```

**Advantages**:
- No annotation processor setup needed
- Can compile any pattern at runtime
- Automatic caching with ConcurrentHashMap
- Hidden classes enable GC-friendly unloading

**Limitations**:
- 5-10ms first-use latency
- Runtime dependency on ASM library
- Requires Java 21+ for hidden classes
- Late error detection (runtime exceptions)

### Shared Compilation Pipeline (reggie-codegen)

**Key Design**: Both paths use the **same** compilation logic from the `reggie-codegen` module:
- `RegexParser`: Pattern → AST
- `ThompsonBuilder`: AST → NFA
- `SubsetConstructor`: NFA → DFA (when possible)
- `PatternAnalyzer`: Pattern structure analysis and strategy selection
- Bytecode generators:
  - Pattern-specific: `GreedyCharClassBytecodeGenerator`, `FixedSequenceBytecodeGenerator`
  - DFA-based: `DFAUnrolledBytecodeGenerator`, `DFASwitchBytecodeGenerator`
  - NFA-based: `NFABytecodeGenerator`

This ensures:
✅ Consistent behavior between compile-time and runtime
✅ Zero code duplication
✅ Easier maintenance and testing

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        COMPILE TIME (javac)                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Source Code:                                                        │
│  ┌───────────────────────────────────────┐                          │
│  │ @RegexPattern("\d{3}-\d{3}-\d{4}")   │                          │
│  │ public abstract ReggieMatcher phone();│                          │
│  └───────────────┬───────────────────────┘                          │
│                  │                                                   │
│                  ▼                                                   │
│  ┌──────────────────────────────────────────┐                       │
│  │   Annotation Processor                    │                       │
│  │   (ReggiProcessor)                        │                       │
│  └──────────────┬────────────────────────────┘                      │
│                 │                                                    │
│    ┌────────────┴─────────────┐                                     │
│    │                           │                                     │
│    ▼                           ▼                                     │
│  Pattern String          Implementation Class                       │
│  "\d{3}-\d{3}-\d{4}"    "ExamplePatterns$Impl"                     │
│    │                                                                 │
│    ▼                                                                 │
│  ┌──────────────┐                                                   │
│  │ RegexParser  │───► AST (Abstract Syntax Tree)                   │
│  └──────────────┘        │                                          │
│                          ▼                                          │
│                    ┌─────────────┐                                  │
│                    │ ConcatNode  │                                  │
│                    │   children: │                                  │
│                    │   - QuantifierNode(\d, 3,3)                   │
│                    │   - LiteralNode('-')                           │
│                    │   - QuantifierNode(\d, 3,3)                   │
│                    │   - LiteralNode('-')                           │
│                    │   - QuantifierNode(\d, 4,4)                   │
│                    └──────┬──────┘                                  │
│                           │                                          │
│                           ▼                                          │
│                    ┌──────────────────┐                             │
│                    │ ThompsonBuilder  │                             │
│                    └──────┬───────────┘                             │
│                           │                                          │
│                           ▼                                          │
│                    NFA (Non-deterministic)                          │
│                    ┌──────────────────────────┐                    │
│                    │ States: 30               │                    │
│                    │ Epsilon transitions: Yes │                    │
│                    │ Backrefs: No             │                    │
│                    └──────┬───────────────────┘                    │
│                           │                                         │
│                           ▼                                         │
│                    ┌──────────────────┐                            │
│                    │ PatternAnalyzer  │                            │
│                    │ - Detect backrefs│                            │
│                    │ - Try DFA build  │                            │
│                    │ - Select strategy│                            │
│                    └──────┬───────────┘                            │
│                           │                                         │
│              ┌────────────┴──────────────┐                         │
│              │ Has Backrefs?             │                         │
│              └─┬─────────────────────┬───┘                         │
│           No   │                 Yes │                             │
│                ▼                     ▼                              │
│         ┌──────────────┐      ┌──────────────┐                    │
│         │SubsetConstructor│    │   Use NFA    │                    │
│         │(NFA→DFA)      │      │  (fallback)  │                    │
│         └──────┬────────┘      └──────┬───────┘                    │
│                │                      │                             │
│                ▼                      │                             │
│         DFA (Deterministic)          │                             │
│         ┌──────────────────┐         │                             │
│         │ States: 14       │         │                             │
│         │ No epsilon       │         │                             │
│         │ Transitions: Map │         │                             │
│         └──────┬───────────┘         │                             │
│                │                      │                             │
│    ┌───────────┴────────────┐        │                             │
│    │ State Count?           │        │                             │
│    ├────────────┬───────────┤        │                             │
│    │            │           │        │                             │
│  <50          50-300      >300       │                             │
│    │            │           │        │                             │
│    ▼            ▼           ▼        ▼                             │
│ ┌──────┐  ┌────────┐  ┌────────┐ ┌───────┐                       │
│ │Unroll│  │Switch  │  │Table   │ │NFA    │                       │
│ │Gen   │  │Gen     │  │Gen(TBD)│ │Gen    │                       │
│ └──┬───┘  └───┬────┘  └───┬────┘ └───┬───┘                       │
│    │          │            │          │                            │
│    └──────────┴────────────┴──────────┘                           │
│                      │                                             │
│                      ▼                                             │
│         ┌─────────────────────────────┐                           │
│         │ ASM Bytecode Generation     │                           │
│         │ - Generate class            │                           │
│         │ - Generate methods          │                           │
│         │ - Inline optimizations      │                           │
│         └────────────┬────────────────┘                           │
│                      │                                             │
│                      ▼                                             │
│         Generated .class file:                                    │
│         PhoneMatcher.class                                        │
│         ┌──────────────────────────┐                              │
│         │ public boolean matches() │                              │
│         │ public boolean find()    │                              │
│         │ public int findFrom()    │                              │
│         └──────────────────────────┘                              │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                         RUNTIME (JVM)                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  User Code:                                                          │
│  ┌──────────────────────────────────────────┐                       │
│  │ ReggieMatcher matcher = patterns.phone();│                       │
│  │ boolean valid = matcher.matches(input);  │                       │
│  └──────────────────┬───────────────────────┘                       │
│                     │                                                │
│                     ▼                                                │
│  ┌────────────────────────────────────────┐                         │
│  │ Generated PhoneMatcher bytecode        │                         │
│  │ - No BitSet allocations                │                         │
│  │ - No Stack allocations                 │                         │
│  │ - Inline character checks              │                         │
│  │ - Direct state jumps                   │                         │
│  └────────────────────────────────────────┘                         │
│                                                                       │
│  Result: 8.5x faster than java.util.regex.Pattern                   │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

---

## Component Details

### 1. Annotation Processor (ReggiProcessor)

**Purpose**: Scans source code for `@RegexPattern` annotations and triggers bytecode generation.

**Key Responsibilities**:
- Discover annotated methods
- Extract pattern strings
- Coordinate compilation pipeline
- Generate implementation classes

**Example**:
```java
@RegexPattern("\\d{3}-\\d{3}-\\d{4}")
public abstract ReggieMatcher phone();
```
→ Generates `PhoneMatcher.class`

---

### 2. Regex Parser (RegexParser)

**Purpose**: Converts pattern strings into Abstract Syntax Tree (AST).

**Parsing Strategy**: Recursive descent parser

**Grammar**:
```
alternation  := concatenation ('|' concatenation)*
concatenation := quantified*
quantified   := atom quantifier?
atom         := char | charClass | group | anchor | backref
quantifier   := '*' | '+' | '?' | '{n}' | '{n,}' | '{n,m}'
```

**Example AST for `[a-z]+@[a-z]+`**:
```
ConcatNode
├─ QuantifierNode (1, ∞)
│  └─ CharClassNode [a-z]
├─ LiteralNode '@'
└─ QuantifierNode (1, ∞)
   └─ CharClassNode [a-z]
```

---

### 3. Thompson NFA Builder (ThompsonBuilder)

**Purpose**: Converts AST to Non-deterministic Finite Automaton.

**Algorithm**: Thompson's construction with epsilon transitions

**Key Features**:
- ε-transitions for concatenation
- Parallel start states for alternation
- Loop edges for quantifiers

**Example NFA for `ab`**:
```
Start ─[a]→ State1 ─[b]→ Accept
```

**Example NFA for `a|b`**:
```
       ┌─[a]→ S1 ─ε→┐
Start ─ε           ε→ Accept
       └─[b]→ S2 ─ε→┘
```

---

### 4. Subset Constructor (SubsetConstructor)

**Purpose**: Converts NFA to DFA using powerset construction.

**Algorithm**:
1. Start with ε-closure of NFA start state
2. For each DFA state and input character:
   - Find all NFA states reachable
   - Compute their ε-closure
   - Create/reuse DFA state for this set
3. Mark DFA states as accepting if they contain NFA accept states

**Optimizations**:
- Pre-compute all epsilon closures once
- Disjoint character set partitioning
- State caching to avoid duplicates
- State explosion detection (>10K states)

**Example - Character Set Partitioning**:
```
Input transitions:
  State A: [a-z] → State B
  State C: [e-m] → State D

After partitioning:
  [a-d] → transitions from A only
  [e-m] → transitions from both A and C
  [n-z] → transitions from A only
```

**DFA for `ab`**:
```
Start ─[a]→ State1 ─[b]→ Accept
    └[other]→ Reject
```

---

### 5. Pattern Analyzer (PatternAnalyzer)

**Purpose**: Analyze patterns and select optimal bytecode generation strategy.

**Decision Tree**:
```
           ┌──────────────────────┐
           │ Has Backreferences?  │
           └──────┬───────────────┘
                  │
          ┌───────┴───────┐
         Yes             No
          │               │
          ▼               ▼
    ┌─────────┐    ┌──────────────┐
    │NFA with │    │Try DFA build │
    │Backrefs │    └──────┬───────┘
    └─────────┘           │
                    ┌─────┴─────┐
              Success        Explosion
                │               │
                ▼               ▼
         ┌──────────────┐  ┌─────────────┐
         │State Count?  │  │Optimized NFA│
         └──┬───────────┘  └─────────────┘
            │
    ┌───────┼───────┐
    │       │       │
  <50    50-300   >300
    │       │       │
    ▼       ▼       ▼
Unrolled Switch  Table
```

**Strategy Selection**:
| States | Strategy | Reason |
|--------|----------|--------|
| <50 | DFA_UNROLLED | Maximum performance, acceptable code size |
| 50-300 | DFA_SWITCH | Good balance of speed and compactness |
| >300 | DFA_TABLE | Memory-efficient for large DFAs |
| Explosion | OPTIMIZED_NFA | Graceful fallback |
| Backrefs | NFA_WITH_BACKREFS | Only NFA can handle |

---

## Bytecode Generation Strategies

Reggie uses a **pattern-first** approach: analyze the pattern structure and generate the simplest, most efficient bytecode possible. The strategy selection is hierarchical, checking for specialized patterns first before falling back to generic automaton-based approaches.

> **Note**: The sections below describe the five core strategies that illustrate the fundamental approaches. The actual codebase includes 20+ additional specialized generators for specific pattern types:
> - Backreference patterns (LINEAR_BACKREFERENCE, FIXED_REPETITION_BACKREF, VARIABLE_CAPTURE_BACKREF, etc.)
> - Lookahead/lookbehind patterns (SPECIALIZED_MULTIPLE_LOOKAHEADS, HYBRID_DFA_LOOKAHEAD, etc.)
> - Quantified groups (SPECIALIZED_QUANTIFIED_GROUP, NESTED_QUANTIFIED_GROUPS, etc.)
> - Recursive patterns (RECURSIVE_DESCENT for subroutines and conditionals)
> - Many pattern-specific optimizations (STATELESS_LOOP, SPECIALIZED_BOUNDED_QUANTIFIERS, etc.)
>
> Each specialized generator applies the same principles shown here but optimized for specific pattern characteristics.

### Strategy Priority Order

1. **Specialized Pattern-Specific Generators** (highest performance)
   - Check pattern structure first
   - Generate minimal, tailored bytecode
   - No state machines or switches when not needed

2. **Generic Automaton-Based Generators** (fallback)
   - DFA-based for patterns without backreferences
   - NFA-based for complex patterns (backrefs, lookaround)

---

### Strategy 0A: Greedy Character Class (Pattern-Specific)

**Pattern Structure**: Single capturing group with character class + greedy quantifier
- Examples: `(\d+)`, `([a-z]*)`, `(\w+)`, `([0-9]{2,5})`

**Detection Logic**:
```java
if (pattern is single GroupNode) {
    if (group contains QuantifierNode(greedy)) {
        if (quantifier contains CharClassNode) {
            → Use SPECIALIZED_GREEDY_CHARCLASS
        }
    }
}
```

**Generated Bytecode Pattern**:
```java
// Completely eliminates state machine
public boolean matches(String input) {
    int pos = 0;
    int len = input.length();

    // Simple while loop - no states, no switches
    while (pos < len) {
        char c = input.charAt(pos);
        // Inline charset check (e.g., for \d)
        if (c < '0' || c > '9') break;
        pos++;
    }

    // Check constraints and return
    return pos >= minMatches && pos == len;
}
```

**Performance**: **33x faster than JDK** for match operations

**Characteristics**:
- Zero state machine overhead
- Simple while loop with inline checks
- Minimal bytecode size (~50-100 bytes)
- JIT-friendly straight-line code
- Full capturing group tracking

**Pros**:
- Extreme simplicity and speed
- Covers ~40% of real-world patterns
- Trivial to understand and maintain
- Perfect for JIT optimization

**Cons**:
- Only handles single char class patterns
- Requires greedy quantifier

---

### Strategy 0B: Fixed-Length Sequences (Pattern-Specific)

**Pattern Structure**: Concatenation of fixed-length elements
- Examples: `\d{3}-\d{3}-\d{4}` (phone), `\d{4}-\d{2}-\d{2}` (date), `(\d{3})-(\d{3})-(\d{4})`

**Detection Logic**:
```java
if (pattern is ConcatNode of fixed elements) {
    if (totalLength < 100 && allElementsFixed) {
        → Use SPECIALIZED_FIXED_SEQUENCE
    }
}
```

**Generated Bytecode Pattern**:
```java
// Completely unrolled - no loops at all
public boolean matches(String input) {
    // Fast length check
    if (input.length() != 12) return false;

    // Unroll all 12 character checks
    char c0 = input.charAt(0);
    if (c0 < '0' || c0 > '9') return false;

    char c1 = input.charAt(1);
    if (c1 < '0' || c1 > '9') return false;

    char c2 = input.charAt(2);
    if (c2 < '0' || c2 > '9') return false;

    char c3 = input.charAt(3);
    if (c3 != '-') return false;

    // ... continue for all 12 positions

    return true;
}
```

**Performance**: **11.4x faster than JDK** for match, **34.6x faster** for position extraction

**Characteristics**:
- Complete unrolling (no loops)
- Inline character tests
- Length check first (fail fast)
- Full capturing group position tracking
- Minimal bytecode (~200-500 bytes depending on length)

**Pros**:
- Extremely fast for validation patterns
- Covers ~25% of real-world patterns (phone, SSN, dates, UUIDs)
- Deterministic performance
- Excellent JIT optimization

**Cons**:
- Limited to patterns < 100 chars
- Only works for fixed-length patterns

---

### Strategy 1: DFA Unrolled (< 50 states)

**Characteristics**:
- Fully unrolled state machine
- Each state = label with inline transitions
- Direct `goto` jumps between states
- Zero allocations

**Generated Bytecode Pattern**:
```java
// Conceptual representation of generated bytecode
public boolean matches(String input) {
    if (input == null) return false;
    int pos = 0;

state_0:  // Start state
    if (pos >= input.length()) return false;
    char ch = input.charAt(pos++);
    if (ch >= '0' && ch <= '9') goto state_1;
    return false;

state_1:  // After first digit
    if (pos >= input.length()) return false;
    ch = input.charAt(pos++);
    if (ch >= '0' && ch <= '9') goto state_2;
    return false;

// ... more states ...

state_14:  // Final state
    if (pos >= input.length()) return true;
    return false;
}
```

**Performance**: 8-20x faster than JDK Pattern

**Pros**:
- Fastest execution
- JIT-friendly (straight-line code)
- Zero allocations
- Predictable branches

**Cons**:
- Large bytecode size
- Not suitable for patterns >50 states

---

### Strategy 2: DFA Switch (50-300 states)

**Characteristics**:
- While loop with switch statement
- State stored in variable
- Inline character checks
- Still zero allocations

**Generated Bytecode Pattern**:
```java
// Conceptual representation
public boolean matches(String input) {
    if (input == null) return false;
    int state = 0;  // Current state
    int pos = 0;

    while (pos < input.length()) {
        char ch = input.charAt(pos++);

        switch (state) {
            case 0:  // Start state
                if (ch >= '0' && ch <= '9') {
                    state = 1;
                    continue;
                }
                return false;

            case 1:  // State 1
                if (ch >= '0' && ch <= '9') {
                    state = 2;
                    continue;
                }
                return false;

            // ... more cases ...

            default:
                return false;
        }
    }

    // Check if final state is accepting
    return state == 14;
}
```

**Performance**: 4-8x faster than JDK Pattern

**Pros**:
- Compact bytecode
- Scales to medium-sized DFAs
- Zero allocations
- JIT can optimize switch to tableswitch

**Cons**:
- Slightly slower than unrolled
- Loop overhead

---

### Strategy 3: Optimized NFA (Fallback)

**Characteristics**:
- BitSet-based state tracking
- Epsilon closure computation
- Handles all regex features

**Generated Bytecode Pattern**:
```java
// Conceptual representation
public boolean matches(String input) {
    if (input == null) return false;

    BitSet currentStates = new BitSet();
    BitSet nextStates = new BitSet();

    // Initialize with start state + epsilon closure
    currentStates.set(0);
    computeEpsilonClosure(currentStates);

    for (int pos = 0; pos < input.length(); pos++) {
        char ch = input.charAt(pos);
        nextStates.clear();

        // Process all active states
        for (int state : currentStates) {
            // Check transitions for this state
            // Add target states to nextStates
        }

        computeEpsilonClosure(nextStates);

        // Swap state sets
        BitSet temp = currentStates;
        currentStates = nextStates;
        nextStates = temp;
    }

    // Check if any accept state is active
    return containsAcceptState(currentStates);
}
```

**Performance**: Similar to JDK Pattern

**Pros**:
- Handles all regex features
- Supports backreferences
- Graceful fallback

**Cons**:
- Allocates BitSet objects
- Slower than DFA approaches
- Not zero-allocation

---

## Performance Characteristics

### Memory Usage

| Strategy | Compile-Time | Runtime Heap | Stack Usage |
|----------|--------------|--------------|-------------|
| DFA Unrolled | High (large .class) | Zero | 2-4 variables |
| DFA Switch | Medium | Zero | 3-5 variables |
| NFA | Low | 2 BitSets + Stack | 7-12 variables |

### Execution Speed

```
Benchmark: Phone pattern "\d{3}-\d{3}-\d{4}"

JDK Pattern:           ████████ 20,000 ops/ms
Reggie NFA:            ████████ 20,000 ops/ms (similar)
Reggie DFA Switch:     ███████████████████████████████████ 70,000 ops/ms (3.5x)
Reggie DFA Unrolled:   ████████████████████████████████████████████████████████████████████ 170,000 ops/ms (8.5x)
```

### Optimization Effectiveness

| Pattern Type | DFA States | Strategy | Speedup |
|--------------|------------|----------|---------|
| Literal `"hello"` | 6 | Unrolled | 19.8x |
| Digits `\d+` | 2 | Unrolled | 14.2x |
| Phone `\d{3}-\d{3}-\d{4}` | 14 | Unrolled | 8.5x |
| Email (simple) | 25 | Unrolled | 6.5x |
| Email (complex) | 80 | Switch | 4.5x |
| URL (very complex) | 250 | Switch | 3.2x |
| Backref `(a+)b\1` | N/A | NFA | 1.0x |

---

## Pros and Cons

### Overall Architecture

#### Pros ✅

1. **Exceptional Performance**
   - 9.1x average speedup over JDK Pattern
   - Up to 19.8x for simple patterns
   - Zero-allocation execution

2. **Compile-Time Optimization**
   - All heavy computation done during compilation
   - No runtime overhead for DFA construction
   - Pattern errors caught at compile-time

3. **Type Safety**
   - Compile-time validation of patterns
   - No runtime PatternSyntaxException for valid patterns
   - IDE autocomplete for pattern methods

4. **Specialized Code**
   - Pattern-specific bytecode
   - Optimal strategy per pattern
   - JIT-friendly code generation

5. **Transparent Fallback**
   - Gracefully handles complex patterns
   - Automatic NFA fallback when needed
   - Still supports advanced features

6. **Small Runtime Footprint**
   - No large runtime library needed
   - Generated code is self-contained
   - Minimal dependencies

#### Cons ❌

1. **Increased Compile Time**
   - DFA construction during annotation processing
   - Can be slow for many complex patterns
   - No caching between builds (yet)

2. **Larger .class Files**
   - Unrolled DFA generates significant bytecode
   - Each pattern = separate class
   - Can impact jar size for many patterns

3. **Compile-Time Only**
   - Cannot handle runtime-dynamic patterns
   - Must know patterns at compile-time
   - String.matches(pattern) still needs JDK Pattern

4. **API Limitations** (compared to JDK Pattern)
   - ⚠️ Group capture: Partial support - works for most patterns, some edge cases with variable quantifiers in capturing groups
   - No replaceAll/split/replaceFirst methods yet
   - No Matcher-style stateful API (no reset(), no region())
   - Multiple operations available: matches(), find(), findFrom(), match(), findMatch()

5. **Feature Limitations**
   - No Unicode properties (\p{L}, \p{N}, etc.)
   - ⚠️ Scoped inline flags not supported ((?i:...) syntax) - global flags work: (?i), (?m), (?s), (?x)
   - No atomic groups ((?>...))
   - No possessive quantifiers (*+, ++, ?+)
   - ⚠️ Self-referencing backreferences limited ((a\1?){4})
   - ✅ Lookahead and lookbehind fully supported
   - ✅ Basic backreferences supported
   - ✅ Subroutines and conditionals supported (basic cases)
   - See [PCRE Conformance Roadmap](plans/pcre-conformance-roadmap.md) for detailed status (91.3% pass rate)

6. **Learning Curve**
   - Different API from java.util.regex
   - Annotation-based approach unfamiliar
   - Need to understand when DFA applies

---

### DFA Unrolled Strategy

#### Pros ✅
- Fastest possible execution
- Zero allocations
- Predictable performance
- JIT loves straight-line code

#### Cons ❌
- Large bytecode size
- Only practical for <50 states
- Can hit method size limits for very complex patterns

---

### DFA Switch Strategy

#### Pros ✅
- Good balance of speed and size
- Scales to 300 states
- Still zero allocations
- Tableswitch optimization by JIT

#### Cons ❌
- Slightly slower than unrolled
- Loop overhead
- Not suitable for >300 states

---

### NFA Fallback Strategy

#### Pros ✅
- Handles all regex features
- Supports backreferences
- No state explosion
- Familiar semantics

#### Cons ❌
- Allocates BitSet/Stack
- Similar speed to JDK Pattern
- No performance advantage
- More complex bytecode

---

## Examples

### Example 1: Phone Number Validation

**Pattern**: `\d{3}-\d{3}-\d{4}`

**Analysis**:
- No backreferences → DFA eligible
- 14 DFA states → Use DFA_UNROLLED
- Expected speedup: ~8x

**Generated States**:
```
S0: Start
S1-S3: First 3 digits
S4: First dash
S5-S7: Middle 3 digits
S8: Second dash
S9-S12: Last 4 digits
S13: Accept
```

**Performance**:
- JDK Pattern: 20,000 ops/ms
- Reggie DFA: 170,000 ops/ms
- **Speedup: 8.5x**

---

### Example 2: Email Validation

**Pattern**: `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`

**Analysis**:
- No backreferences → DFA eligible
- ~80 DFA states → Use DFA_SWITCH
- Expected speedup: ~5x

**Key States**:
- Local part: character class matching
- @ symbol: literal match
- Domain: character class matching
- Dot: literal match
- TLD: 2+ letters

**Performance**:
- JDK Pattern: 6,800 ops/ms
- Reggie DFA: 38,000 ops/ms
- **Speedup: 5.6x**

---

### Example 3: Simple Literal

**Pattern**: `hello`

**Analysis**:
- 6 DFA states → Use DFA_UNROLLED
- Optimal case for specialization

**Generated Logic**:
```java
// Conceptual
state_0: if (ch == 'h') goto state_1; else reject;
state_1: if (ch == 'e') goto state_2; else reject;
state_2: if (ch == 'l') goto state_3; else reject;
state_3: if (ch == 'l') goto state_4; else reject;
state_4: if (ch == 'o') goto state_5; else reject;
state_5: accept;
```

**Performance**:
- JDK Pattern: 25,000 ops/ms
- Reggie DFA: 500,000 ops/ms
- **Speedup: 19.8x** 🔥

---

### Example 4: Backreference (NFA Fallback)

**Pattern**: `(a+)b\1`

**Analysis**:
- Has backreference → Cannot use DFA
- Must use NFA_WITH_BACKREFS
- Performance similar to JDK

**Generated Logic**:
```java
// Conceptual - BitSet-based simulation
BitSet currentStates = new BitSet();
// Track captured groups
String[] groups = new String[groupCount];

// Simulate NFA with group tracking
// Check backreference matches captured text
```

**Performance**:
- JDK Pattern: 15,000 ops/ms
- Reggie NFA: 15,000 ops/ms
- **Speedup: 1.0x** (no advantage, but correct)

---

## Conclusion

Reggie's architecture achieves exceptional performance through:

1. **Compile-time work**: Move complexity from runtime to compile-time
2. **Specialized generation**: Pattern-specific bytecode, not generic interpreter
3. **Smart fallback**: DFA when possible, NFA when necessary
4. **Zero allocations**: Stack-only execution for hot paths

**Best Use Cases**:
- Known patterns at compile-time
- Performance-critical validation (parsers, servers)
- Simple to medium complexity patterns
- High-throughput applications

**When to Use JDK Pattern**:
- Dynamic runtime patterns (though Reggie supports runtime compilation too)
- Need Unicode property support (\p{L}, \p{N}, etc.)
- Need full API (replaceAll, split, stateful Matcher)
- Pattern complexity > 300 DFA states (rare)

**Active Development Areas**:
- Scoped inline flags ((?i:...) syntax)
- Self-referencing backreferences edge cases
- Unicode property support
- replaceAll/split API methods
- Table-driven DFA for very large automata (>300 states)
- Further NFA optimizations
