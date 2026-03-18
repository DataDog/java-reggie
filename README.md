# Reggie - Hybrid Compile-Time and Runtime Optimized Regex for Java

[![Java 8+](https://img.shields.io/badge/Java-8%2B-blue)](https://openjdk.org/)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![CI](https://github.com/DataDog/java-reggie/actions/workflows/ci.yml/badge.svg)](https://github.com/DataDog/java-reggie/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/DataDog/java-reggie/branch/main/graph/badge.svg)](https://codecov.io/gh/DataDog/java-reggie)
[![Coverage: 73%](https://img.shields.io/badge/Coverage-73%25-brightgreen)](doc/coverage-baseline.md)

Reggie is a high-performance Java regex library that provides **two complementary approaches** to pattern matching:

1. **Compile-Time Generation** - Zero runtime overhead via annotation processing
2. **Runtime Compilation** - Lazy bytecode generation with automatic caching

Both approaches use intelligent strategy selection (DFA/NFA) and generate optimized bytecode for **guaranteed linear-time matching** without ReDoS vulnerabilities.

## Table of Contents

- [Why Reggie?](#why-reggie)
- [Quick Start](#quick-start)
  - [Runtime API](#runtime-api-simplest)
  - [Compile-Time API](#compile-time-api-zero-overhead)
- [Performance](#performance)
- [Performance Tuning](#performance-tuning)
- [Installation](#installation)
- [Complete Usage Guide](#complete-usage-guide)
  - [Runtime Patterns](#runtime-patterns)
  - [Compile-Time Patterns](#compile-time-patterns)
  - [Choosing the Right Approach](#choosing-the-right-approach)
- [API Reference](#api-reference)
- [Supported Features](#supported-features)
- [How It Works](#how-it-works)
- [Project Structure](#project-structure)
- [Building from Source](#building-from-source)
- [Contributing](#contributing)
- [License](#license)

## Why Reggie?

Traditional Java regex engines (like `java.util.regex.Pattern`) have several drawbacks:

- **Runtime compilation overhead**: Patterns must be compiled every time your application starts
- **Backtracking complexity**: Worst-case exponential time complexity (ReDoS vulnerabilities)
- **No compile-time validation**: Pattern errors only discovered at runtime
- **Interpreter overhead**: Pattern execution through a generic interpreter

Reggie solves these problems:

| Feature | JDK Pattern | Reggie (Compile-Time) | Reggie (Runtime) |
|---------|-------------|----------------------|------------------|
| **Compilation overhead** | Every startup | Zero (at build time) | First use only (~5-10ms) |
| **ReDoS protection** | ❌ No | ✅ Yes (linear time) | ✅ Yes (linear time) |
| **Error detection** | Runtime | **Compile time** | Runtime |
| **Performance** | Good | **Excellent (10-20x)** | **Excellent (10-20x)** |
| **JIT optimization** | Limited | **Maximum** | **Maximum** |
| **Dynamic patterns** | ✅ Yes | ❌ No | ✅ Yes |

## Quick Start

### Runtime API (Simplest)

No build configuration needed - just use it:

```java
import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.runtime.ReggieMatcher;

public class Example {
    public static void main(String[] args) {
        // Compile pattern once (cached automatically)
        ReggieMatcher phone = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");

        // Use it multiple times - fast!
        System.out.println(phone.matches("123-456-7890")); // true
        System.out.println(phone.matches("invalid"));       // false

        // Find in text
        System.out.println(phone.find("Call 123-456-7890"));  // true
        System.out.println(phone.findFrom("Call 123-456-7890", 0)); // 5
    }
}
```

**First-use latency**: ~5-10ms for pattern compilation, then <1µs cache lookup.

### Compile-Time API (Zero Overhead)

Define patterns at compile time for maximum performance:

**Step 1**: Create a pattern provider class:

```java
// MyPatterns.java
import com.datadoghq.reggie.ReggiePatterns;
import com.datadoghq.reggie.annotations.RegexPattern;
import com.datadoghq.reggie.runtime.ReggieMatcher;

public abstract class MyPatterns implements ReggiePatterns {

    @RegexPattern("\\d{3}-\\d{3}-\\d{4}")
    public abstract ReggieMatcher phone();

    @RegexPattern("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    public abstract ReggieMatcher email();

    @RegexPattern("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
    public abstract ReggieMatcher ipv4();
}
```

**Step 2**: Use the patterns (implementation generated at compile time):

```java
import com.datadoghq.reggie.Reggie;

public class Example {
    public static void main(String[] args) {
        // Get pattern provider instance (generated implementation)
        MyPatterns patterns = Reggie.patterns(MyPatterns.class);

        // Use the matchers - zero overhead!
        System.out.println(patterns.phone().matches("123-456-7890"));  // true
        System.out.println(patterns.email().matches("user@example.com")); // true
        System.out.println(patterns.ipv4().matches("192.168.1.1"));      // true
    }
}
```

**First-use latency**: 0ms (compiled at build time).

## Performance

Reggie achieves **21x speedup** over JDK's `Pattern` and **50x speedup** over RE2J across typical patterns.

### Benchmark Summary (322 benchmarks)

| Engine | Avg Throughput | vs JDK | vs RE2J |
|--------|----------------|--------|---------|
| **Reggie** | 351,917 ops/ms | — | — |
| JDK | 16,745 ops/ms | baseline | 2.4x faster |
| RE2J | 6,999 ops/ms | 2.4x slower | baseline |

### Performance by Category

Latest results from January 2026 benchmark report:

| Category | vs JDK | vs RE2J | Notes |
|----------|--------|---------|-------|
| Match Operations | **13.7x** | **40.6x** | Phone, email, digits |
| Find Operations | **7.9x** | **38.2x** | Pattern searching |
| Group Extraction | **15.0x** | **257.5x** | Capturing groups |
| State Explosion | **389x** | **59.1x** | Catastrophic backtracking patterns (ReDoS immunity) |
| Backreferences | **25.6x** | n/a | RE2J doesn't support backrefs |
| Assertions | **9.1x** | n/a | Lookahead/lookbehind (RE2J doesn't support) |

*RE2J benchmarks excluded for patterns using backreferences and assertions (unsupported features).*

### Why So Fast?

1. **Zero initialization**: No `Pattern.compile()` overhead
2. **Specialized bytecode**: Each pattern gets custom-generated code
3. **Maximum JIT optimization**: HotSpot can fully inline specialized matchers
4. **No interpreter**: Direct execution, no generic pattern interpreter
5. **Linear-time matching**: DFA-based approach eliminates backtracking

### Engine Comparison

| Feature | Reggie | JDK Pattern | RE2J |
|---------|--------|-------------|------|
| Time Complexity | O(n) guaranteed | O(2^n) worst case | O(n) guaranteed |
| Implementation | JIT-compiled bytecode | Interpreted backtracking | NFA simulation |
| ReDoS Safe | ✅ Yes | ❌ No | ✅ Yes |
| Backreferences | ✅ Yes | ✅ Yes | ❌ No |
| Lookahead/Lookbehind | ✅ Yes | ✅ Yes | ❌ No |

**Full Report**: Run `./gradlew :reggie-benchmark:benchmarkAndReport` to generate detailed HTML report

## Performance Tuning

### Optional: Enable Zero-Copy String Access

**TL;DR**: Add this JVM argument for an additional **5-10% performance boost**:
```
--add-opens java.base/java.lang=ALL-UNNAMED
```

#### How It Works

Reggie uses a smart multi-tier strategy for accessing string content during pattern matching:

1. **Zero-Copy Mode** (requires `--add-opens`): Direct access to String's internal byte array via MethodHandles - **fastest**
2. **Copy-Based Mode** (automatic fallback): Copies string bytes once, enables SIMD optimizations - **very fast**
3. **charAt Mode** (for specific patterns): Delegates to `String.charAt()` - **still fast**

**The library works perfectly without any JVM arguments** - it automatically falls back to copy-based or charAt mode. However, adding `--add-opens` eliminates the O(n) copy overhead for an extra performance edge.

#### When Zero-Copy Matters Most

The performance gain from `--add-opens` depends on your patterns:

| Pattern Type | Impact | Example |
|--------------|--------|---------|
| Short strings (<100 chars) | Minimal (~1-2%) | Short validation patterns |
| Long strings + SIMD patterns | Moderate (~5%) | `[0-9a-fA-F]+` on large text |
| Tight loops, hot paths | Noticeable (~10%) | Millions of matches/sec |
| Anchored patterns | None | `^abc` (early bailout) |

#### Adding the JVM Argument

**Gradle**:
```gradle
tasks.withType(JavaExec) {
    jvmArgs '--add-opens', 'java.base/java.lang=ALL-UNNAMED'
}

test {
    jvmArgs '--add-opens', 'java.base/java.lang=ALL-UNNAMED'
}
```

**Maven**:
```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
                <argLine>--add-opens java.base/java.lang=ALL-UNNAMED</argLine>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**Command Line**:
```bash
java --add-opens java.base/java.lang=ALL-UNNAMED -jar your-app.jar
```

**IDE (IntelliJ IDEA)**:
- Run → Edit Configurations
- Add to "VM options": `--add-opens java.base/java.lang=ALL-UNNAMED`

#### Verification

Check if zero-copy is active:
```java
import com.datadoghq.reggie.runtime.StringView;

if (StringView.isZeroCopyAvailable()) {
    System.out.println("Zero-copy optimization enabled!");
} else {
    System.out.println("Using copy-based fallback (still fast!)");
}
```

**Bottom line**: The library works great out-of-box. Add `--add-opens` if you want to squeeze out every last microsecond in high-throughput scenarios.

## Installation

### Gradle

Add to your `build.gradle`:

```gradle
repositories {
    mavenCentral() // or your repository
}

dependencies {
    // For runtime API only
    implementation 'com.datadoghq:reggie-runtime:0.0.1-SNAPSHOT'

    // For compile-time API (annotation processing)
    implementation 'com.datadoghq:reggie-annotations:0.0.1-SNAPSHOT'
    annotationProcessor 'com.datadoghq:reggie-processor:0.0.1-SNAPSHOT'
}

```

### Maven

Add to your `pom.xml`:

```xml
<dependencies>
    <!-- For runtime API -->
    <dependency>
        <groupId>com.datadoghq</groupId>
        <artifactId>reggie-runtime</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </dependency>

    <!-- For compile-time API -->
    <dependency>
        <groupId>com.datadoghq</groupId>
        <artifactId>reggie-annotations</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>com.datadoghq</groupId>
        <artifactId>reggie-processor</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

## Complete Usage Guide

### Runtime Patterns

The runtime API compiles patterns on-demand with automatic caching.

#### Basic Usage

```java
import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.runtime.ReggieMatcher;

// Compile pattern (automatically cached)
ReggieMatcher matcher = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");

// Test if entire string matches
boolean matches = matcher.matches("123-456-7890");  // true

// Find pattern anywhere in string
boolean found = matcher.find("Call 123-456-7890 now");  // true

// Find pattern starting at position
int position = matcher.findFrom("Multiple: 123-456-7890 and 999-888-7777", 0);
// Returns 10 (start of first match)
```

#### Cache Management

```java
// Automatic caching (pattern string is the key)
ReggieMatcher m1 = Reggie.compile("\\d+");
ReggieMatcher m2 = Reggie.compile("\\d+");
assert m1 == m2;  // Same instance returned

// Explicit cache key for user input
String userPattern = getUserInput();
ReggieMatcher matcher = Reggie.cached("user-search-pattern", userPattern);

// Check cache status
System.out.println("Cached patterns: " + Reggie.cacheSize());
System.out.println("Keys: " + Reggie.cachedPatterns());

// Clear cache (e.g., on configuration reload)
Reggie.clearCache();
```

#### Error Handling

```java
try {
    ReggieMatcher matcher = Reggie.compile("[invalid");
} catch (java.util.regex.PatternSyntaxException e) {
    System.err.println("Invalid pattern: " + e.getMessage());
}
```

#### Performance Tips

```java
// ✅ GOOD: Compile once, reuse many times
ReggieMatcher phone = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");
for (String input : inputs) {
    if (phone.matches(input)) {
        // process
    }
}

// ❌ BAD: Don't compile in loops
for (String input : inputs) {
    ReggieMatcher phone = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");  // Cached but wasteful
    if (phone.matches(input)) {
        // process
    }
}
```

### Compile-Time Patterns

The compile-time API generates specialized matchers during build for zero runtime overhead.

#### Step-by-Step Setup

**1. Create Pattern Provider Class**

Create an abstract class implementing `ReggiePatterns` with abstract methods annotated with `@RegexPattern`:

```java
package com.example.patterns;

import com.datadoghq.reggie.ReggiePatterns;
import com.datadoghq.reggie.annotations.RegexPattern;
import com.datadoghq.reggie.runtime.ReggieMatcher;

public abstract class ValidationPatterns implements ReggiePatterns {

    // Simple patterns
    @RegexPattern("\\d+")
    public abstract ReggieMatcher digits();

    @RegexPattern("[a-zA-Z]+")
    public abstract ReggieMatcher letters();

    // Real-world patterns
    @RegexPattern("\\d{3}-\\d{3}-\\d{4}")
    public abstract ReggieMatcher usPhone();

    @RegexPattern("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    public abstract ReggieMatcher email();

    @RegexPattern("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}")
    public abstract ReggieMatcher strongPassword();
}
```

**2. Build Your Project**

The annotation processor runs automatically during compilation:

```bash
./gradlew build
```

Generated files (in `build/generated/sources/annotationProcessor`):
- `ValidationPatterns$Impl.java` - Implementation of your pattern provider
- Individual matcher classes for each pattern

**3. Use the Patterns**

```java
import com.datadoghq.reggie.Reggie;
import com.example.patterns.ValidationPatterns;

public class Validator {
    // Singleton pattern (optional but recommended)
    private static final ValidationPatterns PATTERNS =
        Reggie.patterns(ValidationPatterns.class);

    public boolean isValidEmail(String email) {
        return PATTERNS.email().matches(email);
    }

    public boolean isStrongPassword(String password) {
        return PATTERNS.strongPassword().matches(password);
    }

    public boolean hasDigits(String text) {
        return PATTERNS.digits().find(text);
    }
}
```

#### Pattern Organization

You can organize patterns into multiple classes:

```java
// NetworkPatterns.java
public abstract class NetworkPatterns implements ReggiePatterns {
    @RegexPattern("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
    public abstract ReggieMatcher ipv4();

    @RegexPattern("([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}")
    public abstract ReggieMatcher ipv6();
}

// FilePatterns.java
public abstract class FilePatterns implements ReggiePatterns {
    @RegexPattern(".*\\.java$")
    public abstract ReggieMatcher javaFile();

    @RegexPattern(".*\\.(jpg|png|gif)$")
    public abstract ReggieMatcher imageFile();
}

// Usage
NetworkPatterns net = Reggie.patterns(NetworkPatterns.class);
FilePatterns files = Reggie.patterns(FilePatterns.class);

if (net.ipv4().matches(address)) { /* ... */ }
if (files.javaFile().matches(filename)) { /* ... */ }
```

#### Compile-Time Error Detection

Invalid patterns are caught at build time:

```java
@RegexPattern("[invalid")  // Missing closing bracket
public abstract ReggieMatcher broken();

// Build output:
// error: Invalid regex pattern: Unclosed character class near index 7
//        [invalid
//                ^
```

#### IDE Integration

Modern IDEs (IntelliJ IDEA, VS Code with Java extensions) automatically run annotation processors:

1. **IntelliJ IDEA**: Enable "Annotation Processing" in Settings
2. **VS Code**: Works automatically with Java extension pack
3. **Eclipse**: Enable "Annotation Processing" in project properties

### Choosing the Right Approach

| Use Case | Recommended | Why |
|----------|-------------|-----|
| Known patterns in hot paths | **Compile-Time** | Zero overhead, compile-time validation |
| User-provided search | **Runtime** | Dynamic pattern support |
| Configuration-driven patterns | **Runtime** | Flexibility to change patterns |
| Form validation | **Compile-Time** | Patterns known at build time |
| Log parsing (fixed formats) | **Compile-Time** | Maximum performance |
| Log parsing (user filters) | **Runtime** | User can customize |
| GraalVM native-image | **Compile-Time** | No runtime bytecode generation |

**General Rule**: Use compile-time for static patterns (95% of use cases), runtime for dynamic patterns.

## API Reference

### Runtime API

#### `Reggie` Class

```java
// Compile pattern with automatic caching
public static ReggieMatcher compile(String pattern)

// Compile with explicit cache key
public static ReggieMatcher cached(String key, String pattern)

// Cache management
public static void clearCache()
public static int cacheSize()
public static Set<String> cachedPatterns()
```

#### `ReggieMatcher` Class

```java
// Test if entire string matches
public abstract boolean matches(String input)

// Find pattern anywhere in string
public abstract boolean find(String input)

// Find pattern starting at position
public abstract int findFrom(String input, int start)
// Returns: start position of match, or -1 if not found

// Get the pattern string
public final String pattern()
```

### Compile-Time API

#### `@RegexPattern` Annotation

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface RegexPattern {
    String value();  // The regex pattern
}
```

**Requirements**:
- Applied to abstract methods
- Method must return `ReggieMatcher`
- Method must take no parameters
- Containing class must be abstract and implement `ReggiePatterns`

#### `Reggie.patterns()` Method

```java
public static <T extends ReggiePatterns> T patterns(Class<T> patternClass)
```

Returns an instance of the generated implementation class.

## Supported Features

**PCRE Compatibility: 94.3%** (364/386 tests passing from PCRE test suite)

### ✅ Fully Supported

- **Character classes**: `[abc]`, `[a-z]`, `[^abc]`, `[a-zA-Z0-9]`
- **Predefined classes**: `\d`, `\w`, `\s` (and negated: `\D`, `\W`, `\S`)
- **Quantifiers**: `*`, `+`, `?`, `{n}`, `{n,}`, `{n,m}`
  - Whitespace inside quantifiers: `{ 3, 5 }`, `{ 3 }` (PCRE compatible)
- **Escape sequences**:
  - Basic: `\n`, `\t`, `\r`, `\\`, `\/`
  - Octal: `\100` (octal 100 = '@'), `\377`
  - Hex: `\x40` (hex 40 = '@'), `\xFF`
- **Alternation**: `|`
- **Groups**:
  - Capturing: `(...)`
  - Non-capturing: `(?:...)`
  - Named groups: `(?<name>...)` (in progress)
- **Anchors**:
  - Line: `^`, `$`
  - String: `\A` (absolute start), `\Z` (end before optional newline)
  - Word: `\b` (word boundary), `\B` (non-word boundary)
- **Lookahead**: `(?=...)`, `(?!...)` (positive/negative)
- **Lookbehind**: `(?<=...)`, `(?<!...)` (positive/negative)
- **Inline modifiers**:
  - Case-insensitive: `(?i)`
  - Dotall mode: `(?s)` - `.` matches newlines
  - Multiline: `(?m)`
  - Extended: `(?x)` - ignore whitespace and comments
- **Backreferences**: `\1`, `\2`, etc. (with limitations - see below)

### 🚧 Partial Support / Limitations

- **Capturing group extraction**: Basic support for fixed-width patterns
  - Works: `(abc)\1` matches "abcabc"
  - Limited: Variable-width patterns with quantifiers require backtracking
- **Backreferences**: Supported with limitations
  - Fixed quantifiers work: `(a{2})\1` matches "aaaa"
  - Variable quantifiers have limitations: `(a+)\1` matches minimal cases only
  - Specialized patterns optimized: `<(\w+)>.*</\1>` (HTML tags)
- **Non-greedy quantifiers**: `*?`, `+?`, `??`
  - Basic support, complex interactions with lookahead/backref limited

### ❌ Not Yet Supported

- **Subroutine calls**: `(?1)`, `(?R)` - recursive pattern matching (PCRE extension)
- **Conditional patterns**: `(?(condition)yes|no)` - partial support, edge cases remain
- **Possessive quantifiers**: `*+`, `++`, `?+`
- **Atomic groups**: `(?>...)`
- **Unicode categories**: `\p{L}`, `\p{N}`, `\P{L}` (planned)
- **Unicode properties**: `\p{Script=Greek}`, etc.
- **Scoped inline modifiers**: `(?i:...)` (global modifiers work: `(?i)`)

### Recent Improvements (January 2026)

- ✅ Whitespace in quantifiers (PCRE compatible)
- ✅ Octal escape sequences (`\100`, `\377`)
- ✅ Hex escape sequences (`\x40`, `\xFF`)
- ✅ Dotall mode `(?s)` - dot matches newlines
- ✅ Absolute string anchors `\A` and `\Z`
- ✅ Greedy backtracking for complex patterns

See [PCRE Conformance Roadmap](doc/plans/pcre-conformance-roadmap.md) for detailed compatibility status.

## How It Works

### Pattern Analysis & Strategy Selection

Reggie analyzes each pattern and selects the optimal matching strategy:

```
Pattern Analysis Decision Tree:
│
├─ Has backreferences? ───────────────────────► Thompson NFA (bytecode)
│
├─ Has lookahead/lookbehind? ─────────────────► Hybrid DFA+NFA (bytecode)
│
├─ Pure regular (no extended features)?
│  │
│  ├─ Simple pattern (<50 states)? ──────────► Pure DFA Unrolled (bytecode)
│  │
│  ├─ Medium complexity (50-500 states)? ────► Pure DFA Switch (bytecode)
│  │
│  └─ Complex pattern (>500 states)? ────────► Thompson NFA (bytecode)
│
└─ Unsupported features? ─────────────────────► Compile-time error
```

### Generated Code Examples

#### Literal Pattern (`hello`)

Generated matcher:

```java
public boolean matches(String input) {
    return input != null && input.equals("hello");
}

public boolean find(String input) {
    return input != null && input.contains("hello");
}
```

#### Phone Number (`\d{3}-\d{3}-\d{4}`)

Generated matcher (simplified):

```java
public boolean matches(String input) {
    if (input == null || input.length() != 12) return false;
    int pos = 0;

    // Check 3 digits
    for (int i = 0; i < 3; i++) {
        if (!Character.isDigit(input.charAt(pos++))) return false;
    }

    // Check dash
    if (input.charAt(pos++) != '-') return false;

    // Check 3 digits
    for (int i = 0; i < 3; i++) {
        if (!Character.isDigit(input.charAt(pos++))) return false;
    }

    // Check dash
    if (input.charAt(pos++) != '-') return false;

    // Check 4 digits
    for (int i = 0; i < 4; i++) {
        if (!Character.isDigit(input.charAt(pos++))) return false;
    }

    return pos == input.length();
}
```

#### Complex Pattern (DFA with Switch)

For patterns with multiple states, generates switch-based DFA:

```java
public boolean matches(String input) {
    if (input == null) return false;
    int state = 0;  // Initial state

    for (int i = 0; i < input.length(); i++) {
        char c = input.charAt(i);
        switch (state) {
            case 0: state = transition0(c); break;
            case 1: state = transition1(c); break;
            // ... more states
            case -1: return false;  // Error state
        }
    }

    return isAcceptState(state);
}
```

### Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                    Reggie API                        │
│  ┌──────────────┐              ┌─────────────────┐  │
│  │ Compile-Time │              │    Runtime      │  │
│  │   Patterns   │              │    Patterns     │  │
│  │ @RegexPattern│              │ Reggie.compile()│  │
│  └──────┬───────┘              └────────┬────────┘  │
└─────────┼──────────────────────────────┼───────────┘
          │                              │
          │                              │
    ┌─────▼─────────┐            ┌───────▼──────────┐
    │  Annotation   │            │     Runtime      │
    │  Processor    │            │    Compiler      │
    │ (Build Time)  │            │  (First Use)     │
    └───────┬───────┘            └────────┬─────────┘
            │                             │
            └──────────┬──────────────────┘
                       │
            ┌──────────▼──────────┐
            │   Shared Codegen    │
            │  ┌──────────────┐   │
            │  │ AST Parser   │   │
            │  │ NFA Builder  │   │
            │  │ DFA Builder  │   │
            │  │ Bytecode Gen │   │
            │  └──────────────┘   │
            └─────────────────────┘
                       │
            ┌──────────▼──────────┐
            │  Generated Matcher  │
            │    (Bytecode)       │
            └─────────────────────┘
```

## Project Structure

```
reggie/
├── reggie-annotations/     # @RegexPattern annotation definition
├── reggie-codegen/         # Shared bytecode generation (AST, NFA, DFA, codegen)
├── reggie-processor/       # Annotation processor (compile-time path)
├── reggie-runtime/         # Runtime API + interfaces
├── reggie-benchmark/       # Performance benchmarks and examples
├── reggie-integration-tests/ # PCRE/RE2 conformance test suites
└── doc/                    # Documentation and research notes
```

**Design Principle**: The `reggie-codegen` module contains all pattern analysis and bytecode generation logic, shared by both the annotation processor (compile-time) and runtime compiler. This eliminates code duplication and ensures consistent behavior.

## Building from Source

### Requirements

- Java 8+
- Gradle 8.11+

### Build Commands

```bash
# Clone repository
git clone https://github.com/DataDog/java-reggie.git
cd java-reggie

# Build all modules
./gradlew build

# Run tests
./gradlew test

# Run benchmarks
./gradlew :reggie-benchmark:run

# Run JMH benchmarks
./gradlew :reggie-benchmark:jmh

# Clean build
./gradlew clean build
```

### Running Examples

```bash
# Simple matcher tests with performance comparison
./gradlew :reggie-benchmark:run

# Expected output:
# Testing generated matchers...
#
# === Phone Matcher ===
# Phone Matcher: PASSED
#
# === Hello Matcher ===
# Hello Matcher: PASSED
#
# === Performance Comparison ===
# Reggie matcher: 17.09 ms
# JDK Pattern:    190.72 ms
# Speedup:        11.16x
```

## Research & References

Reggie is based on decades of regex engine research:

- [Regular Expression Matching Can Be Simple And Fast](https://swtch.com/~rsc/regexp/regexp1.html) - Russ Cox (2007)
  - Thompson's NFA construction algorithm
  - Linear-time matching without backtracking

- [RE2: Google's linear-time regex engine](https://github.com/google/re2)
  - Production-proven DFA/NFA hybrid approach
  - Guaranteed linear-time performance

- [.NET Regex Source Generators](https://learn.microsoft.com/en-us/dotnet/standard/base-types/compilation-and-reuse-in-regular-expressions) (.NET 7+)
  - Compile-time regex generation (proves concept viability)

- [Needle: DFA-based regex with bytecode compilation](https://justinblank.com/experiments/needle.html)
  - Java bytecode generation for regex matchers

- [PCRE (Perl Compatible Regular Expressions)](https://www.pcre.org/)
  - Industry-standard regex compatibility target
  - Reggie achieves 94.3% compatibility (364/386 tests)

### Novel Contributions

Based on extensive research, Reggie's hybrid compile-time/runtime approach is **novel** in the Java ecosystem:

- **No existing annotation-based compile-time regex generators for Java**
- Combines .NET's source generation concept with Java annotation processing
- Unified codegen for both compile-time and runtime paths
- Industry-proven hybrid DFA/NFA strategy
- High PCRE compatibility (94.3%) with linear-time guarantees

## Contributing

This is an experimental project, but contributions are welcome!

### Areas for Contribution

- **Performance optimization**: Improve generated code quality
- **Feature implementation**: Capturing groups, backreferences
- **Benchmark suite**: More comprehensive performance tests
- **Documentation**: Tutorials, examples, use cases
- **Testing**: Edge cases, correctness validation

### Development Setup

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes with tests
4. Run tests: `./gradlew test`
5. Submit a pull request

See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines.

## Maintainer

**Jaroslav Bachořík** (@jbachorik)
Email: jaroslav.bachorik@datadoghq.com

For security issues, please see [SECURITY.md](SECURITY.md).

## License

Apache License 2.0 - see [LICENSE](LICENSE) file for details

## Acknowledgments

- Russ Cox for [foundational regex research](https://swtch.com/~rsc/regexp/)
- Google for [RE2](https://github.com/google/re2)
- The Java community for ASM library and annotation processing
- .NET team for proving source generation viability

---

**Status**: Experimental / Proof of Concept

**Author**: Jaroslav Bachorik

**Questions?** Open an issue on [GitHub](https://github.com/DataDog/java-reggie)
