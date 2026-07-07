# Runtime Pattern Compilation Tutorial

This tutorial shows you how to use Reggie's runtime pattern compilation for **dynamic patterns** with **automatic caching** and **lazy bytecode generation**.

## Table of Contents

- [What is Runtime Compilation?](#what-is-runtime-compilation)
- [Quick Start](#quick-start)
- [Detailed Tutorial](#detailed-tutorial)
  - [Step 1: Setup Dependencies](#step-1-setup-dependencies)
  - [Step 2: Basic Pattern Compilation](#step-2-basic-pattern-compilation)
  - [Step 3: Cache Management](#step-3-cache-management)
  - [Step 4: Error Handling](#step-4-error-handling)
  - [Step 5: Match Results and Named Groups](#step-5-match-results-and-named-groups)
  - [Step 6: Splitting Input](#step-6-splitting-input)
  - [Step 7: Streaming Replacement](#step-7-streaming-replacement)
- [Real-World Examples](#real-world-examples)
- [Best Practices](#best-practices)
- [Performance Optimization](#performance-optimization)
- [Troubleshooting](#troubleshooting)
- [Advanced Topics](#advanced-topics)

## What is Runtime Compilation?

Runtime compilation means regex patterns are:

1. **Compiled on first use** - No compile-time dependency on patterns
2. **Automatically cached** - Thread-safe caching prevents duplicate compilation
3. **Lazy bytecode generation** - Only compiled when actually used
4. **Hidden classes** - Uses Java 21+ hidden classes for efficient memory use

**PCRE Compatibility**: Reggie achieves 95.4% PCRE compatibility (329/345 evaluated tests), supporting most regex features including octal/hex escapes, whitespace in quantifiers, dotall mode, lookahead/lookbehind, and backreferences. See the [PCRE Conformance Roadmap](plans/pcre-conformance-roadmap.md) for current status.

### Benefits

| Aspect | Value |
|--------|-------|
| **Dynamic patterns** | Accept patterns from users, config files, etc. |
| **No build dependency** | Patterns can change without rebuilding |
| **Auto caching** | Transparent caching for performance |
| **Thread-safe** | Safe for concurrent use |
| **Performance** | **10-20x faster** than JDK Pattern after first use |

### When to Use

✅ **Use runtime** when:
- Patterns come from user input (search queries, filters)
- Patterns are loaded from configuration files
- Patterns change frequently
- You need maximum flexibility

❌ **Don't use** when:
- Patterns are known at build time → use compile-time API
- You need absolute minimum latency → use compile-time API
- Deploying to restrictive environments (no bytecode generation)

## Quick Start

**1. Add dependency** (`build.gradle`):

```gradle
dependencies {
    implementation 'com.datadoghq:reggie:<version>'
}
```

**2. Use in your code**:

```java
import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.runtime.ReggieMatcher;

public class Example {
    public static void main(String[] args) {
        // Compile pattern (cached automatically)
        ReggieMatcher phone = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");

        // Use it
        System.out.println(phone.matches("123-456-7890"));  // true
        System.out.println(phone.find("Call 123-456-7890"));  // true
    }
}
```

That's it! No build configuration, no annotation processing.

## Detailed Tutorial

### Step 1: Setup Dependencies

#### Gradle

Add to your `build.gradle`:

```gradle
plugins {
    id 'java'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.datadoghq:reggie:<version>'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

#### Maven

Add to your `pom.xml`:

```xml
<properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
</properties>

<dependencies>
    <dependency>
        <groupId>com.datadoghq</groupId>
        <artifactId>reggie</artifactId>
        <version><!-- version --></version>
    </dependency>
</dependencies>
```

### Step 2: Basic Pattern Compilation

#### Simple Compilation

```java
import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.runtime.ReggieMatcher;

public class BasicExample {
    public static void main(String[] args) {
        // Compile pattern - automatically cached
        ReggieMatcher matcher = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");

        // Test if entire string matches
        boolean matches = matcher.matches("123-456-7890");
        System.out.println("Matches: " + matches);  // true

        // Find pattern anywhere in string
        boolean found = matcher.find("Call me at 123-456-7890");
        System.out.println("Found: " + found);  // true

        // Find with position
        int position = matcher.findFrom("Numbers: 123-456-7890 and 999-888-7777", 0);
        System.out.println("First match at: " + position);  // 9
    }
}
```

#### Compilation Latency

First compilation: ~5-10ms per pattern
Subsequent uses: <1µs (cache lookup)

```java
// First use - compiles pattern (~5-10ms)
ReggieMatcher first = Reggie.compile("\\d+");

// Second use - returns cached instance (<1µs)
ReggieMatcher second = Reggie.compile("\\d+");

assert first == second;  // Same instance!
```

### Step 3: Cache Management

#### Automatic Caching

Reggie automatically caches patterns using the pattern string as the key:

```java
// All of these return the same cached instance
ReggieMatcher m1 = Reggie.compile("\\d+");
ReggieMatcher m2 = Reggie.compile("\\d+");
ReggieMatcher m3 = Reggie.compile("\\d+");

assert m1 == m2 && m2 == m3;  // All same instance
```

#### Custom Cache Keys

For user-provided patterns, use explicit cache keys:

```java
// User provides pattern from UI
String userPattern = getUserInput();

// Use custom key to control caching
ReggieMatcher matcher = Reggie.cached("user-search-pattern", userPattern);

// Even if pattern changes, same key = same cache slot
String newPattern = getUserInput();
ReggieMatcher updated = Reggie.cached("user-search-pattern", newPattern);
// If pattern different, old one replaced
```

#### Inspecting Cache

```java
// Check cache size
int size = Reggie.cacheSize();
System.out.println("Cached patterns: " + size);

// Get all cached pattern strings/keys
Set<String> keys = Reggie.cachedPatterns();
for (String key : keys) {
    System.out.println("Cached: " + key);
}
```

#### Clearing Cache

```java
// Clear entire cache (frees memory)
Reggie.clearCache();
System.out.println("Cache cleared, size: " + Reggie.cacheSize());  // 0

// Future compiles will regenerate bytecode
ReggieMatcher m = Reggie.compile("\\d+");  // Recompiles
```

**When to clear cache**:
- On configuration reload
- During testing (between test classes)
- Memory pressure situations
- Application shutdown

### Step 4: Error Handling

#### Invalid Pattern Syntax

```java
import java.util.regex.PatternSyntaxException;

public class ErrorHandling {
    public static void main(String[] args) {
        try {
            // Invalid pattern - unclosed bracket
            ReggieMatcher matcher = Reggie.compile("[invalid");
        } catch (PatternSyntaxException e) {
            System.err.println("Invalid pattern: " + e.getMessage());
            System.err.println("At index: " + e.getIndex());
            System.err.println("Description: " + e.getDescription());
        }
    }
}
```

#### Null Input Handling

```java
ReggieMatcher matcher = Reggie.compile("\\d+");

// Null-safe - returns false for null input
boolean matches = matcher.matches(null);  // false
boolean found = matcher.find(null);       // false
int position = matcher.findFrom(null, 0); // -1
```

#### Safe Pattern Compilation Helper

```java
public class SafeCompiler {
    public static Optional<ReggieMatcher> tryCompile(String pattern) {
        try {
            return Optional.of(Reggie.compile(pattern));
        } catch (PatternSyntaxException e) {
            System.err.println("Invalid pattern: " + pattern);
            return Optional.empty();
        }
    }

    public static void main(String[] args) {
        // Safe compilation
        Optional<ReggieMatcher> matcher = tryCompile("[invalid");

        matcher.ifPresent(m -> {
            // Use matcher
        });
    }
}
```

#### Unsupported Constructs and JDK Fallback

By default, `Reggie.compile()` throws `UnsupportedPatternException` (a `RuntimeException`
subclass) for constructs it cannot compile natively, rather than silently delegating to
`java.util.regex`:

```java
try {
    ReggieMatcher matcher = Reggie.compile("^((.)(?1)\\2|.?)$"); // recursive palindrome
} catch (UnsupportedPatternException e) {
    System.err.println("Not supported natively: " + e.getMessage());
}
```

To opt into JDK delegation for such patterns instead of failing, use
`Reggie.compileAllowingFallback()` or pass `ReggieOption.ALLOW_JDK_FALLBACK` explicitly via
`ReggieOptions`:

```java
// Convenience method
ReggieMatcher matcher = Reggie.compileAllowingFallback("^((.)(?1)\\2|.?)$");

// Equivalent, explicit form — also useful for combining with other options
ReggieOptions options = ReggieOptions.builder().allowJdkFallback().build();
ReggieMatcher matcher2 = Reggie.compile("^((.)(?1)\\2|.?)$", options);
```

`ReggieOptions` also supports `CAPTURE_NAMED_ONLY` (via `.namedOnly()`), which tracks only named
and backreference-target capturing groups instead of every group, matching `java.util.regex`
numbering only when explicitly requested.

#### Escape Sequences

Reggie supports octal and hex escape sequences for matching special characters:

```java
// Hex escapes: \xhh (where h is hex digit)
ReggieMatcher atSign = Reggie.compile("\\x40");  // @ character
assertTrue(atSign.matches("@"));

// Octal escapes: \nnn (where n is 0-7)
ReggieMatcher octal100 = Reggie.compile("\\100");  // @ character (octal 100 = decimal 64)
assertTrue(octal100.matches("@"));

// In character classes
ReggieMatcher hexRange = Reggie.compile("[\\x41-\\x5A]");  // A-Z
assertTrue(hexRange.matches("M"));

// Whitespace in quantifiers (PCRE compatible)
ReggieMatcher spaced = Reggie.compile("a{ 3, 5 }");  // Same as a{3,5}
assertTrue(spaced.matches("aaaa"));
```

### Step 5: Match Results and Named Groups

`match()` tests the entire input and returns a `MatchResult` (or `null` on no match). `findMatch()` finds the first occurrence anywhere in the string. Both give you access to the full match text and any captured groups.

```java
import com.datadoghq.reggie.runtime.MatchResult;

ReggieMatcher dated = Reggie.compile("(\\d{4})-(\\d{2})-(\\d{2})");

// Entire-string match
MatchResult r = dated.match("2026-05-08");
if (r != null) {
    System.out.println(r.group());    // "2026-05-08" — entire match
    System.out.println(r.group(1));   // "2026"
    System.out.println(r.group(2));   // "05"
    System.out.println(r.group(3));   // "08"
    System.out.println(r.start(1));   // 0
    System.out.println(r.end(1));     // 4
}

// First-occurrence search
MatchResult first = dated.findMatch("On 2026-05-08 and 2026-05-09");
System.out.println(first.group());  // "2026-05-08"
```

#### Named Groups

Use `(?<name>...)` (or `(?'name'...)`) to name capturing groups, then retrieve them by name:

```java
ReggieMatcher m = Reggie.compile("(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2})");
MatchResult r = m.match("2026-05-08");

System.out.println(r.group("year"));   // "2026"
System.out.println(r.group("month"));  // "05"
System.out.println(r.group("day"));    // "08"
System.out.println(r.start("year"));   // 0
System.out.println(r.end("year"));     // 4
```

A group that did not participate in the match returns `null` from `group(name)` and `-1` from `start(name)` / `end(name)`. Passing an unknown or `null` name throws `IllegalArgumentException`.

### Step 6: Splitting Input

`split(String input)` splits around each match, discarding trailing empty strings. The `split(String input, int limit)` overload mirrors `java.util.regex.Pattern.split(input, limit)` exactly:

```java
ReggieMatcher comma = Reggie.compile(",");

// No limit (default): trailing empty strings discarded
String[] parts = comma.split("a,b,c,");         // ["a", "b", "c"]

// limit > 0: at most limit parts; last part is the unparsed remainder
String[] two = comma.split("a,b,c,d", 2);       // ["a", "b,c,d"]

// limit < 0: all parts, trailing empty strings retained
String[] all = comma.split("a,b,", -1);         // ["a", "b", ""]

// limit = 0: explicit default, trailing empty strings discarded
String[] zero = comma.split("a,b,", 0);         // ["a", "b"]
```

### Step 7: Streaming Replacement

`MatchCursor` is a stateful streaming cursor — the Reggie equivalent of JDK's `Matcher.appendReplacement()` / `appendTail()`. Create one with `matcher.cursor(input)`, advance through matches with `findNext()`, and call `appendReplacement()` / `appendTail()` to build the result:

```java
import com.datadoghq.reggie.runtime.MatchCursor;
import com.datadoghq.reggie.runtime.MatchResult;

ReggieMatcher m = Reggie.compile("(\\w+)@(\\w+)");
String input = "alice@example and bob@test";
StringBuilder sb = new StringBuilder();

try (MatchCursor cursor = m.cursor(input)) {
    MatchResult r;
    while ((r = cursor.findNext()) != null) {
        cursor.appendReplacement(sb, "$2/$1");
    }
    cursor.appendTail(sb);
}
System.out.println(sb);  // "example/alice and test/bob"
```

#### Replacement Syntax

| Token | Meaning |
|-------|---------|
| `$0` | entire match |
| `$1`, `$2`, … | numbered capture groups |
| `${name}` | named capture groups |
| `\$` | literal `$` |
| `\\` | literal `\` |

#### Iterator API

`MatchCursor` implements `Iterator<MatchResult>`, so you can use `hasNext()` / `next()` or `forEachRemaining()`:

```java
try (MatchCursor cursor = m.cursor("alice@example and bob@test")) {
    cursor.forEachRemaining(r -> System.out.println(r.group()));
}
```

#### Reusing a Cursor

`reset(String newInput)` repoints the cursor at new input without allocating a new object:

```java
MatchCursor cursor = m.cursor("alice@example");
// ... process first input ...
cursor.reset("bob@test");  // reuse on different input
// ... process second input ...
```

#### Error Conditions

- `appendReplacement` before `findNext` throws `IllegalStateException`
- Calling `appendTail` a second time throws `IllegalStateException`
- Unknown or malformed group reference in the replacement string throws `IllegalArgumentException`

## Real-World Examples

### Example 1: User Search Feature

```java
public class SearchEngine {
    private static final int MAX_PATTERN_LENGTH = 1000;

    public List<String> search(List<String> documents, String userQuery) {
        // Validate user query
        if (userQuery == null || userQuery.isEmpty()) {
            return Collections.emptyList();
        }
        if (userQuery.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("Query too long");
        }

        // Sanitize if needed (escape special chars for literal search)
        String pattern = userQuery;  // Or Pattern.quote(userQuery) for literal

        try {
            // Compile with custom cache key
            ReggieMatcher matcher = Reggie.cached("user-search", pattern);

            // Filter documents
            return documents.stream()
                .filter(doc -> matcher.find(doc))
                .collect(Collectors.toList());
        } catch (PatternSyntaxException e) {
            // Invalid regex - could do literal search instead
            System.err.println("Invalid search pattern: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
```

### Example 2: Configuration-Driven Validation

```java
public class ConfigurableValidator {
    private final Map<String, ReggieMatcher> validators = new ConcurrentHashMap<>();

    // Load validators from config file
    public void loadConfig(Properties config) {
        Reggie.clearCache();  // Clear old patterns
        validators.clear();

        // Load patterns from config
        String emailPattern = config.getProperty("validation.email");
        String phonePattern = config.getProperty("validation.phone");
        String zipPattern = config.getProperty("validation.zip");

        // Compile and cache
        validators.put("email", Reggie.compile(emailPattern));
        validators.put("phone", Reggie.compile(phonePattern));
        validators.put("zip", Reggie.compile(zipPattern));
    }

    public boolean validate(String type, String value) {
        ReggieMatcher matcher = validators.get(type);
        if (matcher == null) {
            throw new IllegalArgumentException("Unknown validation type: " + type);
        }
        return matcher.matches(value);
    }

    // Example config file:
    // validation.email=[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}
    // validation.phone=\\d{3}-\\d{3}-\\d{4}
    // validation.zip=\\d{5}(-\\d{4})?
}
```

### Example 3: Log Filter Application

```java
public class LogFilter {
    private final List<ReggieMatcher> includePatterns = new ArrayList<>();
    private final List<ReggieMatcher> excludePatterns = new ArrayList<>();

    public void addIncludePattern(String pattern) {
        includePatterns.add(Reggie.compile(pattern));
    }

    public void addExcludePattern(String pattern) {
        excludePatterns.add(Reggie.compile(pattern));
    }

    public boolean shouldInclude(String logLine) {
        // If no include patterns, include by default
        boolean included = includePatterns.isEmpty() ||
            includePatterns.stream().anyMatch(m -> m.find(logLine));

        // Check exclude patterns
        boolean excluded = excludePatterns.stream()
            .anyMatch(m -> m.find(logLine));

        return included && !excluded;
    }

    public Stream<String> filterLogs(Stream<String> logs) {
        return logs.filter(this::shouldInclude);
    }

    public static void main(String[] args) throws Exception {
        LogFilter filter = new LogFilter();

        // Include only ERROR or WARN lines
        filter.addIncludePattern("(?i)(ERROR|WARN)");

        // Exclude known noisy errors
        filter.addExcludePattern("Connection reset by peer");

        // Process log file
        try (Stream<String> lines = Files.lines(Path.of("/var/log/app.log"))) {
            filter.filterLogs(lines)
                .forEach(System.out::println);
        }
    }
}
```

### Example 4: Data Extraction and Validation

```java
public class DataExtractor {
    // Reusable matchers
    private static final ReggieMatcher EMAIL = Reggie.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final ReggieMatcher PHONE = Reggie.compile(
        "\\d{3}-\\d{3}-\\d{4}");
    private static final ReggieMatcher URL = Reggie.compile(
        "https?://[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/[^\\s]*)?");

    public static class Contact {
        String email;
        String phone;
        String website;
    }

    public List<Contact> extractContacts(String text) {
        List<Contact> contacts = new ArrayList<>();

        for (String block : text.split("\\n\\n")) {
            MatchResult emailMatch = EMAIL.findMatch(block);
            MatchResult phoneMatch = PHONE.findMatch(block);
            MatchResult urlMatch   = URL.findMatch(block);

            if (emailMatch == null && phoneMatch == null && urlMatch == null) {
                continue;
            }
            Contact contact = new Contact();
            if (emailMatch != null) contact.email   = emailMatch.group();
            if (phoneMatch != null) contact.phone   = phoneMatch.group();
            if (urlMatch   != null) contact.website = urlMatch.group();
            contacts.add(contact);
        }

        return contacts;
    }
}
```

### Example 5: Dynamic Field Validation

```java
public class DynamicFormValidator {
    public static class ValidationRule {
        String fieldName;
        String pattern;
        String errorMessage;

        public ValidationRule(String fieldName, String pattern, String errorMessage) {
            this.fieldName = fieldName;
            this.pattern = pattern;
            this.errorMessage = errorMessage;
        }
    }

    private final List<ValidationRule> rules = new ArrayList<>();

    public void addRule(String fieldName, String pattern, String errorMessage) {
        rules.add(new ValidationRule(fieldName, pattern, errorMessage));
    }

    public Map<String, String> validate(Map<String, String> formData) {
        Map<String, String> errors = new HashMap<>();

        for (ValidationRule rule : rules) {
            String value = formData.get(rule.fieldName);
            if (value == null) {
                errors.put(rule.fieldName, "Field is required");
                continue;
            }

            try {
                // Compile pattern dynamically
                ReggieMatcher matcher = Reggie.cached(
                    "field-" + rule.fieldName,
                    rule.pattern
                );

                if (!matcher.matches(value)) {
                    errors.put(rule.fieldName, rule.errorMessage);
                }
            } catch (PatternSyntaxException e) {
                errors.put(rule.fieldName, "Invalid validation pattern");
            }
        }

        return errors;
    }

    public static void main(String[] args) {
        DynamicFormValidator validator = new DynamicFormValidator();

        // Load rules from database or config
        validator.addRule("email", "[a-z]+@[a-z]+", "Invalid email");
        validator.addRule("phone", "\\d{3}-\\d{3}-\\d{4}", "Invalid phone");
        validator.addRule("age", "[1-9]\\d?", "Age must be 1-99");

        // Validate form data
        Map<String, String> form = new HashMap<>();
        form.put("email", "user@example.com");
        form.put("phone", "123-456-7890");
        form.put("age", "25");

        Map<String, String> errors = validator.validate(form);
        if (errors.isEmpty()) {
            System.out.println("Form valid!");
        } else {
            errors.forEach((field, error) ->
                System.err.println(field + ": " + error));
        }
    }
}
```

## Best Practices

### 1. Compile Once, Reuse Many Times

```java
// ✅ GOOD: Compile once outside loop
ReggieMatcher phone = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");
for (String number : phoneNumbers) {
    if (phone.matches(number)) {
        // process
    }
}

// ❌ BAD: Compiling in loop (even with caching, wasteful)
for (String number : phoneNumbers) {
    if (Reggie.compile("\\d{3}-\\d{3}-\\d{4}").matches(number)) {
        // process
    }
}
```

### 2. Use Custom Cache Keys for User Input

```java
// ✅ GOOD: Explicit key for user patterns
String userPattern = getUserInput();
ReggieMatcher matcher = Reggie.cached("user-search", userPattern);

// ❌ RISKY: Automatic caching with user input
// Cache can grow unbounded if users provide many different patterns
String userPattern = getUserInput();
ReggieMatcher matcher = Reggie.compile(userPattern);
```

### 3. Validate User Patterns

```java
public ReggieMatcher compileUserPattern(String pattern) {
    // Validate length
    if (pattern.length() > 1000) {
        throw new IllegalArgumentException("Pattern too long");
    }

    // Validate complexity (optional - count alternations, groups, etc.)
    if (pattern.split("\\|").length > 100) {
        throw new IllegalArgumentException("Pattern too complex");
    }

    try {
        return Reggie.cached("user-pattern", pattern);
    } catch (PatternSyntaxException e) {
        throw new IllegalArgumentException("Invalid pattern: " + e.getMessage());
    }
}
```

### 4. Handle Errors Gracefully

```java
public List<String> search(List<String> items, String pattern) {
    ReggieMatcher matcher;

    try {
        matcher = Reggie.compile(pattern);
    } catch (PatternSyntaxException e) {
        // Fallback to literal search
        System.err.println("Invalid pattern, using literal search");
        String literal = Pattern.quote(pattern);
        matcher = Reggie.compile(literal);
    }

    return items.stream()
        .filter(item -> matcher.find(item))
        .collect(Collectors.toList());
}
```

### 5. Clear Cache When Appropriate

```java
public class ApplicationLifecycle {
    public void onConfigReload() {
        // Clear pattern cache on config reload
        Reggie.clearCache();
        // Reload new patterns...
    }

    public void onShutdown() {
        // Clear cache on shutdown (optional, helps with clean shutdown)
        Reggie.clearCache();
    }
}
```

### 6. Use Static Matchers for Known Patterns

```java
public class Patterns {
    // Known patterns - compile once at class load
    private static final ReggieMatcher EMAIL =
        Reggie.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final ReggieMatcher PHONE =
        Reggie.compile("\\d{3}-\\d{3}-\\d{4}");

    public static ReggieMatcher email() { return EMAIL; }
    public static ReggieMatcher phone() { return PHONE; }
}

// Usage:
if (Patterns.email().matches(input)) {
    // ...
}
```

## Performance Optimization

### Compilation Cost

```java
// Measure compilation time
long start = System.nanoTime();
ReggieMatcher matcher = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");
long compileTime = System.nanoTime() - start;
System.out.printf("Compilation: %.2f ms%n", compileTime / 1_000_000.0);
// Typically 5-10ms

// Cache lookup cost
start = System.nanoTime();
ReggieMatcher cached = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");
long cacheTime = System.nanoTime() - start;
System.out.printf("Cache lookup: %.2f µs%n", cacheTime / 1_000.0);
// Typically <1µs
```

### Matching Performance

```java
ReggieMatcher reggie = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");
Pattern jdk = Pattern.compile("\\d{3}-\\d{3}-\\d{4}");

String input = "123-456-7890";
int iterations = 1_000_000;

// Reggie performance
long start = System.nanoTime();
for (int i = 0; i < iterations; i++) {
    reggie.matches(input);
}
long reggieTime = System.nanoTime() - start;

// JDK performance
start = System.nanoTime();
for (int i = 0; i < iterations; i++) {
    jdk.matcher(input).matches();
}
long jdkTime = System.nanoTime() - start;

System.out.printf("Reggie: %.2f ms%n", reggieTime / 1_000_000.0);
System.out.printf("JDK:    %.2f ms%n", jdkTime / 1_000_000.0);
System.out.printf("Speedup: %.1fx%n", (double) jdkTime / reggieTime);
// Typical: 10-20x speedup
```

### Memory Usage

```java
// Check cache size
System.out.println("Cached patterns: " + Reggie.cacheSize());

// Estimate memory per pattern: ~10-50KB depending on complexity
// Total cache memory ≈ cacheSize() * 30KB (average)

// If memory is tight:
if (Reggie.cacheSize() > 1000) {
    Reggie.clearCache();  // Free memory
}
```

### Zero-Copy String Access Optimization (Advanced)

For **maximum performance** in high-throughput scenarios, enable zero-copy string access:

#### Quick Setup

Add this JVM argument for an additional **5-10% performance boost**:
```
--add-opens java.base/java.lang=ALL-UNNAMED
```

**Note**: The library works perfectly without this - it's purely an optimization for extreme performance needs.

#### How It Works

Reggie intelligently selects one of three string access strategies based on your pattern:

```java
// Pattern analysis determines optimal strategy:

// Strategy 1: Zero-copy (with --add-opens)
[0-9a-fA-F]+              // Direct byte array access, SIMD enabled
                          // ~0.1ns per char, zero setup cost

// Strategy 2: Copy-based (automatic fallback)
.*error.*                 // Copy once, then fast access + SIMD
                          // ~0.5ns setup per byte, then ~0.1ns per char

// Strategy 3: charAt delegation
^[A-Z]{5}$                // Minimal setup, delegates to String.charAt()
                          // Zero setup, ~1.5ns per char (but only checks 5 chars!)
```

**Key insight**: For most patterns, the library automatically chooses the optimal strategy without `--add-opens`.

#### Enabling Zero-Copy

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

#### Verification

Check if zero-copy is active:
```java
import com.datadoghq.reggie.runtime.StringView;

if (StringView.isZeroCopyAvailable()) {
    System.out.println("✓ Zero-copy optimization enabled!");
} else {
    System.out.println("Using optimized fallback (still fast!)");
}
```

#### Performance Impact

The benefit depends on your workload:

| Scenario | Performance Gain |
|----------|------------------|
| **Short strings** (<100 chars) | Minimal (~1-2%) |
| **SIMD patterns** (`[0-9a-fA-F]+`) on long strings | Moderate (~5%) |
| **Tight loops** (millions of matches/sec) | Noticeable (~10%) |
| **Early-bailout patterns** (`^abc`) | None (already optimal) |

**Benchmark example**:
```java
ReggieMatcher hexPattern = Reggie.compile("[0-9a-fA-F]+");
String longHexString = "deadbeef".repeat(1000); // 8000 chars

// Without --add-opens: ~2000ns per match
// With --add-opens:    ~1800ns per match (~10% faster)
```

**Bottom line**: Add `--add-opens` if you're processing large volumes of text or running in performance-critical hot paths. Otherwise, the automatic fallback is excellent.

### Allocation-Free Matching (Advanced)

For hot paths that need group boundaries but want to avoid `MatchResult` allocation, `ReggieMatcher`
offers array-output variants that write directly into caller-provided arrays:

```java
ReggieMatcher matcher = Reggie.compile("(\\d+)-(\\d+)");

// matchInto / findMatchInto: group boundaries into caller arrays.
// Arrays must be at least groupCount()+1 long; group 0 is the whole match.
int[] starts = new int[3];
int[] ends = new int[3];
if (matcher.findMatchInto("id 42-7", starts, ends)) {
    String group1 = "id 42-7".substring(starts[1], ends[1]); // "42"
}

// findMatchInto with an explicit start offset
matcher.findMatchInto("id 42-7", 3, starts, ends);

// findBoundsFrom: only the overall match bounds, no group tracking at all —
// cheapest option when you only need "did it match, and where"
int[] bounds = new int[2];
if (matcher.findBoundsFrom("id 42-7", 0, bounds)) {
    System.out.println("Matched [" + bounds[0] + ", " + bounds[1] + ")");
}
```

`matchInto` is the array-output equivalent of `match()`; it requires the whole input to match.
All three throw `IndexOutOfBoundsException` if the supplied arrays are too small for the pattern's
group count, and leave the arrays unchanged on a failed match.

## Troubleshooting

### Problem: Slow First Use

**Symptom**: First pattern compilation takes 10-50ms

**Explanation**: This is normal - bytecode generation is happening

**Solutions**:
```java
// 1. Pre-compile known patterns at startup
public class Startup {
    static {
        // Pre-compile common patterns
        Reggie.compile("\\d+");
        Reggie.compile("[a-z]+");
        Reggie.compile("\\d{3}-\\d{3}-\\d{4}");
    }
}

// 2. Use compile-time API for known patterns instead
// See TUTORIAL-COMPILE-TIME.md
```

### Problem: Memory Leak from Unbounded Cache

**Symptom**: Memory keeps growing with user-provided patterns

**Cause**: Each unique pattern is cached

**Solutions**:
```java
// 1. Use explicit cache keys
Reggie.cached("user-search", userPattern);  // Only one cache slot

// 2. Limit pattern count
if (Reggie.cacheSize() > MAX_PATTERNS) {
    Reggie.clearCache();
}

// 3. Periodic cache clearing
scheduledExecutor.scheduleAtFixedRate(
    Reggie::clearCache,
    1, 1, TimeUnit.HOURS
);
```

### Problem: PatternSyntaxException

**Symptom**: Exception on pattern compilation

**Solution**: Validate and handle errors

```java
public Optional<ReggieMatcher> safeCompile(String pattern) {
    try {
        return Optional.of(Reggie.compile(pattern));
    } catch (PatternSyntaxException e) {
        logger.warn("Invalid pattern: {}", pattern, e);
        return Optional.empty();
    }
}
```

### Problem: Performance Not as Expected

**Checklist**:
1. Are you reusing matchers or recompiling in loop?
2. Is the pattern already cached (check first-use vs subsequent)?
3. Is the input very long? (Performance scales with input size)
4. Is the pattern very complex? (May fall back to NFA strategy)

## Advanced Topics

### Cache Implementation Details

Reggie uses `ConcurrentHashMap` for thread-safe caching:

```java
// Internal implementation (conceptual):
private static final Map<String, ReggieMatcher> cache =
    new ConcurrentHashMap<>();

public static ReggieMatcher compile(String pattern) {
    return cache.computeIfAbsent(pattern, p -> {
        // Compile pattern to bytecode
        // Generate hidden class
        // Return matcher instance
    });
}
```

### Hidden Classes

Reggie uses Java 21's Hidden Classes API for efficient memory:

- Not visible via reflection
- Can be unloaded when no longer referenced
- Slightly better performance than regular classes

### Bytecode Generation Strategy

Runtime compilation uses the same strategy selection as compile-time:

| Pattern Type | Strategy | Time Complexity |
|-------------|----------|-----------------|
| Simple | DFA Unrolled | O(N) |
| Medium | DFA Switch | O(N) |
| Complex | Thompson NFA | O(MN) |
| With assertions | Hybrid DFA+NFA | O(MN) |

### Thread Safety

All Reggie APIs are thread-safe:

```java
// Safe concurrent compilation
ExecutorService executor = Executors.newFixedThreadPool(10);

for (int i = 0; i < 100; i++) {
    executor.submit(() -> {
        ReggieMatcher m = Reggie.compile("\\d+");
        // Use matcher...
    });
}
```

### Comparison with JDK Pattern

| Feature | Reggie Runtime | JDK Pattern |
|---------|---------------|-------------|
| Thread safety | ✅ Yes | ✅ Yes |
| Reusability | ✅ Yes | ✅ Yes (via Matcher) |
| Performance | **10-20x faster** | Baseline |
| First-use cost | 5-10ms | 1-2ms |
| Caching | Automatic | Manual |
| ReDoS protection | ✅ Yes | ❌ No |
| Capturing groups | ✅ Yes (`MatchResult`) | ✅ Yes (`Matcher`) |
| Named groups | ✅ Yes (`group(String)`) | ✅ Yes (`group(String)`) |
| Streaming replacement | ✅ Yes (`MatchCursor`) | ✅ Yes (`Matcher`) |
| Split with limit | ✅ Yes | ✅ Yes |

---

## Next Steps

- Read the [Compile-Time API Tutorial](TUTORIAL-COMPILE-TIME.md) for static patterns
- See [Fallback Behavior & Known Limitations](agents-fallback-and-limitations.md) for correctness
  guarantees and known gaps
- Read the [main README](../README.md) for the full API surface

## Questions?

- Open an issue on [GitHub](https://github.com/DataDog/java-reggie)
- Read the [main README](../README.md)
