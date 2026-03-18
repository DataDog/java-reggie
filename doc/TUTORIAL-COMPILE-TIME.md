# Compile-Time Pattern Compilation Tutorial

This tutorial shows you how to use Reggie's compile-time pattern compilation via annotation processing for **zero runtime overhead** and **compile-time error detection**.

## Table of Contents

- [What is Compile-Time Compilation?](#what-is-compile-time-compilation)
- [Quick Start](#quick-start)
- [Detailed Tutorial](#detailed-tutorial)
  - [Step 1: Setup Dependencies](#step-1-setup-dependencies)
  - [Step 2: Create Pattern Provider Class](#step-2-create-pattern-provider-class)
  - [Step 3: Build and Generate Code](#step-3-build-and-generate-code)
  - [Step 4: Use the Patterns](#step-4-use-the-patterns)
- [Real-World Examples](#real-world-examples)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)
- [Advanced Topics](#advanced-topics)

## What is Compile-Time Compilation?

Compile-time compilation means your regex patterns are:

1. **Analyzed at build time** - Invalid patterns fail compilation
2. **Converted to bytecode** - Specialized Java classes generated for each pattern
3. **Optimized aggressively** - JIT compiler can fully inline the generated code
4. **Zero overhead** - No Pattern.compile() cost at runtime

**PCRE Compatibility**: Reggie achieves 94.3% PCRE compatibility (364/386 tests), supporting most regex features including octal/hex escapes, whitespace in quantifiers, dotall mode, lookahead/lookbehind, and backreferences.

### Benefits

| Aspect | Value |
|--------|-------|
| **First-use latency** | **0ms** (already compiled) |
| **Error detection** | **Compile time** (fail fast) |
| **Performance** | **10-20x faster** than JDK Pattern |
| **Type safety** | Patterns accessed via generated methods |
| **Refactoring** | IDE refactoring works on pattern accessors |

### When to Use

✅ **Use compile-time** when:
- Patterns are known at build time
- Performance is critical
- You want compile-time validation
- Deploying to GraalVM native-image

❌ **Don't use** when:
- Patterns come from user input
- Patterns are loaded from configuration files
- You need to change patterns without rebuilding

## Quick Start

**1. Add dependencies** (`build.gradle`):

```gradle
dependencies {
    implementation 'com.datadoghq:reggie-annotations:0.0.1-SNAPSHOT'
    annotationProcessor 'com.datadoghq:reggie-processor:0.0.1-SNAPSHOT'
}
```

**2. Create pattern provider**:

```java
import com.datadoghq.reggie.ReggiePatterns;
import com.datadoghq.reggie.annotations.RegexPattern;
import com.datadoghq.reggie.runtime.ReggieMatcher;

public abstract class MyPatterns implements ReggiePatterns {
    @RegexPattern("\\d{3}-\\d{3}-\\d{4}")
    public abstract ReggieMatcher phone();
}
```

**3. Build project**:

```bash
./gradlew build
```

**4. Use the patterns**:

```java
import com.datadoghq.reggie.Reggie;

MyPatterns patterns = Reggie.patterns(MyPatterns.class);
System.out.println(patterns.phone().matches("123-456-7890"));  // true
```

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
    // Reggie annotations (contains @RegexPattern)
    implementation 'com.datadoghq:reggie-annotations:0.0.1-SNAPSHOT'

    // Annotation processor (generates code at build time)
    annotationProcessor 'com.datadoghq:reggie-processor:0.0.1-SNAPSHOT'
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

### Step 2: Create Pattern Provider Class

Create a new Java file (e.g., `ValidationPatterns.java`):

```java
package com.example.patterns;

import com.datadoghq.reggie.ReggiePatterns;
import com.datadoghq.reggie.annotations.RegexPattern;
import com.datadoghq.reggie.runtime.ReggieMatcher;

/**
 * Validation patterns for common use cases.
 * The implementation is generated at compile time by the annotation processor.
 */
public abstract class ValidationPatterns implements ReggiePatterns {

    // Phone number: (123) 456-7890 or 123-456-7890
    @RegexPattern("\\d{3}-\\d{3}-\\d{4}")
    public abstract ReggieMatcher phone();

    // Email address: user@example.com
    @RegexPattern("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    public abstract ReggieMatcher email();

    // Only digits
    @RegexPattern("\\d+")
    public abstract ReggieMatcher digits();

    // Only letters (case-insensitive not yet supported, use [a-zA-Z])
    @RegexPattern("[a-zA-Z]+")
    public abstract ReggieMatcher letters();

    // IPv4 address: 192.168.1.1
    @RegexPattern("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
    public abstract ReggieMatcher ipv4();

    // URL: http://example.com or https://example.com/path
    @RegexPattern("https?://[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?")
    public abstract ReggieMatcher url();

    // Strong password: At least 8 chars, 1 uppercase, 1 digit, 1 special
    @RegexPattern("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}")
    public abstract ReggieMatcher strongPassword();
}
```

#### Pattern Provider Requirements

Your pattern provider class MUST:
1. Be **abstract**
2. Implement `ReggiePatterns` (marker interface)
3. Have methods that are:
   - **Abstract** (no body)
   - **Return `ReggieMatcher`**
   - **Take no parameters**
   - **Annotated with `@RegexPattern`**

#### Supported Pattern Features

Your patterns can use all PCRE-compatible features:

```java
public abstract class AdvancedPatterns implements ReggiePatterns {

    // Hex escapes for special characters
    @RegexPattern("\\x40")  // @ character
    public abstract ReggieMatcher atSign();

    // Octal escapes
    @RegexPattern("\\100")  // @ character (octal 100 = decimal 64)
    public abstract ReggieMatcher octalAt();

    // Whitespace in quantifiers (PCRE compatible)
    @RegexPattern("a{ 3, 5 }")  // Same as a{3,5}
    public abstract ReggieMatcher spacedQuantifier();

    // Dotall mode - dot matches newlines
    @RegexPattern("(?s).*error.*")
    public abstract ReggieMatcher errorAnyWhere();

    // Lookahead for password validation
    @RegexPattern("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}")
    public abstract ReggieMatcher strongPassword();

    // Backreferences for matching repeated words
    @RegexPattern("\\b(\\w+)\\s+\\1\\b")
    public abstract ReggieMatcher repeatedWord();
}

### Step 3: Build and Generate Code

Build your project:

```bash
./gradlew build
```

The annotation processor runs automatically and generates:

**Generated files** (in `build/generated/sources/annotationProcessor`):

1. **`ValidationPatterns$Impl.java`** - Implementation of your pattern provider:

```java
package com.example.patterns;

import com.datadoghq.reggie.runtime.ReggieMatcher;

public class ValidationPatterns$Impl extends ValidationPatterns {

    // Lazy-initialized, thread-safe matcher instances
    private volatile ReggieMatcher phone;
    private volatile ReggieMatcher email;
    // ... etc

    @Override
    public ReggieMatcher phone() {
        if (phone == null) {
            synchronized (this) {
                if (phone == null) {
                    phone = new PhoneMatcher();
                }
            }
        }
        return phone;
    }

    // Similar for other patterns...
}
```

2. **Individual matcher classes** (e.g., `PhoneMatcher.java`):

```java
package com.example.patterns;

import com.datadoghq.reggie.runtime.ReggieMatcher;

public final class PhoneMatcher extends ReggieMatcher {

    public PhoneMatcher() {
        super("\\d{3}-\\d{3}-\\d{4}");
    }

    @Override
    public boolean matches(String input) {
        // Highly optimized bytecode generated here
        if (input == null || input.length() != 12) return false;
        // ... specialized matching code
    }

    @Override
    public boolean find(String input) {
        // ... optimized find implementation
    }

    @Override
    public int findFrom(String input, int start) {
        // ... optimized findFrom implementation
    }
}
```

3. **Service provider file** - Registers the implementation with Java's ServiceLoader:
   - `META-INF/services/com.example.patterns.ValidationPatterns`

### Step 4: Use the Patterns

Now use the generated patterns in your code:

#### Basic Usage

```java
package com.example;

import com.datadoghq.reggie.Reggie;
import com.example.patterns.ValidationPatterns;

public class Validator {

    public static void main(String[] args) {
        // Get the pattern provider instance (generated implementation)
        ValidationPatterns patterns = Reggie.patterns(ValidationPatterns.class);

        // Test phone number
        boolean validPhone = patterns.phone().matches("123-456-7890");
        System.out.println("Valid phone: " + validPhone);  // true

        // Test email
        boolean validEmail = patterns.email().matches("user@example.com");
        System.out.println("Valid email: " + validEmail);  // true

        // Test strong password
        String password = "SecureP@ss1";
        boolean strongPwd = patterns.strongPassword().matches(password);
        System.out.println("Strong password: " + strongPwd);  // true
    }
}
```

#### Singleton Pattern (Recommended)

For best performance, create a singleton instance:

```java
public class Patterns {
    // Singleton instance - created once, reused everywhere
    private static final ValidationPatterns INSTANCE =
        Reggie.patterns(ValidationPatterns.class);

    // Static accessor
    public static ValidationPatterns get() {
        return INSTANCE;
    }

    // Prevent instantiation
    private Patterns() {}
}

// Usage:
if (Patterns.get().email().matches(input)) {
    // ...
}
```

## Real-World Examples

### Example 1: Form Validation

```java
public abstract class FormPatterns implements ReggiePatterns {

    @RegexPattern("[a-zA-Z]{2,50}")
    public abstract ReggieMatcher name();

    @RegexPattern("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    public abstract ReggieMatcher email();

    @RegexPattern("\\d{3}-\\d{3}-\\d{4}")
    public abstract ReggieMatcher phone();

    @RegexPattern("\\d{5}(-\\d{4})?")
    public abstract ReggieMatcher zipCode();

    @RegexPattern("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}")
    public abstract ReggieMatcher password();
}

// Validator class
public class FormValidator {
    private static final FormPatterns PATTERNS = Reggie.patterns(FormPatterns.class);

    public List<String> validateRegistration(RegistrationForm form) {
        List<String> errors = new ArrayList<>();

        if (!PATTERNS.name().matches(form.getName())) {
            errors.add("Invalid name");
        }
        if (!PATTERNS.email().matches(form.getEmail())) {
            errors.add("Invalid email");
        }
        if (!PATTERNS.phone().matches(form.getPhone())) {
            errors.add("Invalid phone number");
        }
        if (!PATTERNS.zipCode().matches(form.getZipCode())) {
            errors.add("Invalid ZIP code");
        }
        if (!PATTERNS.password().matches(form.getPassword())) {
            errors.add("Password must be at least 8 chars with uppercase, digit, and special char");
        }

        return errors;
    }
}
```

### Example 2: Log Parsing

```java
public abstract class LogPatterns implements ReggiePatterns {

    // Apache Common Log Format
    @RegexPattern("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
    public abstract ReggieMatcher ipAddress();

    // ISO 8601 timestamp
    @RegexPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")
    public abstract ReggieMatcher timestamp();

    // HTTP method
    @RegexPattern("GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH")
    public abstract ReggieMatcher httpMethod();

    // HTTP status code
    @RegexPattern("[1-5]\\d{2}")
    public abstract ReggieMatcher statusCode();

    // Error patterns
    @RegexPattern("(?i)error|exception|failed|fatal")
    public abstract ReggieMatcher error();
}

// Log analyzer
public class LogAnalyzer {
    private static final LogPatterns PATTERNS = Reggie.patterns(LogPatterns.class);

    public LogEntry parseLine(String line) {
        LogEntry entry = new LogEntry();

        // Extract IP address
        int ipPos = PATTERNS.ipAddress().findFrom(line, 0);
        if (ipPos >= 0) {
            // Extract IP (note: capturing groups not yet supported)
            // For now, you'd need to extract manually
        }

        // Check for errors
        if (PATTERNS.error().find(line)) {
            entry.setLevel("ERROR");
        }

        return entry;
    }

    public boolean isServerError(String statusCode) {
        return statusCode.startsWith("5") &&
               PATTERNS.statusCode().matches(statusCode);
    }
}
```

### Example 3: Network Utilities

```java
public abstract class NetworkPatterns implements ReggiePatterns {

    // IPv4: 192.168.1.1
    @RegexPattern("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
    public abstract ReggieMatcher ipv4();

    // IPv6: 2001:0db8:85a3:0000:0000:8a2e:0370:7334
    @RegexPattern("([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}")
    public abstract ReggieMatcher ipv6();

    // MAC address: 00:1A:2B:3C:4D:5E
    @RegexPattern("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    public abstract ReggieMatcher macAddress();

    // Domain name: example.com or subdomain.example.com
    @RegexPattern("[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*")
    public abstract ReggieMatcher domain();

    // Email: user@example.com
    @RegexPattern("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    public abstract ReggieMatcher email();

    // URL: http://example.com or https://example.com/path?query=value
    @RegexPattern("https?://[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/[^\\s]*)?")
    public abstract ReggieMatcher url();
}

// Network validator
public class NetworkValidator {
    private static final NetworkPatterns NET = Reggie.patterns(NetworkPatterns.class);

    public static boolean isValidIPv4(String ip) {
        return NET.ipv4().matches(ip);
    }

    public static boolean isValidIPv6(String ip) {
        return NET.ipv6().matches(ip);
    }

    public static boolean isValidMAC(String mac) {
        return NET.macAddress().matches(mac);
    }

    public static boolean isValidDomain(String domain) {
        return NET.domain().matches(domain);
    }
}
```

### Example 4: File System Patterns

```java
public abstract class FilePatterns implements ReggiePatterns {

    // Java source file
    @RegexPattern(".*\\.java$")
    public abstract ReggieMatcher javaFile();

    // Image files
    @RegexPattern(".*\\.(jpg|jpeg|png|gif|bmp|webp)$")
    public abstract ReggieMatcher imageFile();

    // Document files
    @RegexPattern(".*\\.(pdf|doc|docx|txt|md)$")
    public abstract ReggieMatcher documentFile();

    // Archive files
    @RegexPattern(".*\\.(zip|tar|gz|rar|7z)$")
    public abstract ReggieMatcher archiveFile();

    // Unix path
    @RegexPattern("/([^/]+/)*[^/]+")
    public abstract ReggieMatcher unixPath();

    // Windows path
    @RegexPattern("[A-Z]:\\\\([^\\\\]+\\\\)*[^\\\\]+")
    public abstract ReggieMatcher windowsPath();
}

// File filter
public class FileFilter {
    private static final FilePatterns FILES = Reggie.patterns(FilePatterns.class);

    public static boolean isJavaSource(String filename) {
        return FILES.javaFile().matches(filename);
    }

    public static boolean isImage(String filename) {
        return FILES.imageFile().matches(filename);
    }

    public static List<Path> filterByPattern(List<Path> files, ReggieMatcher matcher) {
        return files.stream()
            .filter(path -> matcher.matches(path.getFileName().toString()))
            .collect(Collectors.toList());
    }
}
```

## Best Practices

### 1. Organize Patterns by Domain

Create separate pattern provider classes for different domains:

```java
// NetworkPatterns.java - Network-related patterns
public abstract class NetworkPatterns implements ReggiePatterns {
    @RegexPattern("...") public abstract ReggieMatcher ipv4();
    @RegexPattern("...") public abstract ReggieMatcher email();
}

// FilePatterns.java - File-related patterns
public abstract class FilePatterns implements ReggiePatterns {
    @RegexPattern("...") public abstract ReggieMatcher javaFile();
    @RegexPattern("...") public abstract ReggieMatcher imageFile();
}

// ValidationPatterns.java - Form validation patterns
public abstract class ValidationPatterns implements ReggiePatterns {
    @RegexPattern("...") public abstract ReggieMatcher phone();
    @RegexPattern("...") public abstract ReggieMatcher zipCode();
}
```

### 2. Use Descriptive Method Names

```java
// ✅ GOOD: Clear, descriptive names
@RegexPattern("\\d{3}-\\d{3}-\\d{4}")
public abstract ReggieMatcher usPhoneNumber();

@RegexPattern("\\d{5}(-\\d{4})?")
public abstract ReggieMatcher usZipCode();

// ❌ BAD: Ambiguous names
@RegexPattern("\\d{3}-\\d{3}-\\d{4}")
public abstract ReggieMatcher pattern1();

@RegexPattern("\\d{5}(-\\d{4})?")
public abstract ReggieMatcher p2();
```

### 3. Document Patterns

Add JavaDoc to document what each pattern matches:

```java
/**
 * Matches US phone numbers in format: 123-456-7890
 * @return matcher for US phone numbers
 */
@RegexPattern("\\d{3}-\\d{3}-\\d{4}")
public abstract ReggieMatcher usPhoneNumber();

/**
 * Matches email addresses according to simplified RFC 5322.
 * Supports most common email formats.
 * @return matcher for email addresses
 */
@RegexPattern("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
public abstract ReggieMatcher email();
```

### 4. Use Singleton Pattern for Pattern Providers

```java
public class Patterns {
    // Single instance, created once
    private static final ValidationPatterns VALIDATION =
        Reggie.patterns(ValidationPatterns.class);
    private static final NetworkPatterns NETWORK =
        Reggie.patterns(NetworkPatterns.class);
    private static final FilePatterns FILES =
        Reggie.patterns(FilePatterns.class);

    public static ValidationPatterns validation() { return VALIDATION; }
    public static NetworkPatterns network() { return NETWORK; }
    public static FilePatterns files() { return FILES; }

    private Patterns() {}
}

// Usage:
if (Patterns.validation().email().matches(input)) {
    // ...
}
```

### 5. Keep Patterns Simple and Focused

```java
// ✅ GOOD: Simple, focused pattern
@RegexPattern("\\d{3}-\\d{3}-\\d{4}")
public abstract ReggieMatcher usPhoneSimple();

// ❌ BAD: Overly complex pattern trying to handle everything
@RegexPattern("(\\+\\d{1,3}[- ]?)?((\\(\\d{1,4}\\))|\\d{1,4})[- ]?\\d{1,4}[- ]?\\d{1,9}")
public abstract ReggieMatcher phoneComplex();
```

### 6. Test Your Patterns

Write unit tests for your patterns:

```java
public class ValidationPatternsTest {
    private static final ValidationPatterns patterns =
        Reggie.patterns(ValidationPatterns.class);

    @Test
    public void testPhonePattern() {
        // Valid phones
        assertTrue(patterns.phone().matches("123-456-7890"));
        assertTrue(patterns.phone().matches("000-000-0000"));

        // Invalid phones
        assertFalse(patterns.phone().matches("123-456-789"));   // too short
        assertFalse(patterns.phone().matches("123-456-78901")); // too long
        assertFalse(patterns.phone().matches("abc-def-ghij"));  // letters
    }

    @Test
    public void testEmailPattern() {
        // Valid emails
        assertTrue(patterns.email().matches("user@example.com"));
        assertTrue(patterns.email().matches("test.user+tag@domain.co.uk"));

        // Invalid emails
        assertFalse(patterns.email().matches("invalid"));
        assertFalse(patterns.email().matches("@example.com"));
        assertFalse(patterns.email().matches("user@"));
    }
}
```

## Troubleshooting

### Problem: "No service provider found"

**Error**:
```
IllegalArgumentException: No service provider found for com.example.patterns.ValidationPatterns
```

**Causes**:
1. Project not built after adding patterns
2. Annotation processor not configured correctly
3. Pattern class doesn't implement `ReggiePatterns`

**Solutions**:
```bash
# 1. Clean and rebuild
./gradlew clean build

# 2. Verify annotation processor in build.gradle
dependencies {
    annotationProcessor 'com.datadoghq:reggie-processor:0.0.1-SNAPSHOT'
}

# 3. Ensure class implements ReggiePatterns
public abstract class ValidationPatterns implements ReggiePatterns {
    // ...
}
```

### Problem: Pattern Compilation Error

**Error**:
```
error: Invalid regex pattern: Unclosed character class near index 7
       [invalid
               ^
```

**Solution**: Fix the pattern syntax. The error is caught at **compile time**, not runtime!

```java
// ❌ WRONG: Unclosed bracket
@RegexPattern("[invalid")
public abstract ReggieMatcher broken();

// ✅ CORRECT: Properly closed bracket
@RegexPattern("[invalid]")
public abstract ReggieMatcher fixed();
```

### Problem: IDE Not Generating Code

**Symptoms**: IDE shows errors, generated classes not found

**Solutions**:

**IntelliJ IDEA**:
1. Enable annotation processing: `Settings` → `Build, Execution, Deployment` → `Compiler` → `Annotation Processors`
2. Check "Enable annotation processing"
3. Rebuild project: `Build` → `Rebuild Project`

**VS Code**:
1. Ensure Java Extension Pack is installed
2. Reload window: `Ctrl+Shift+P` → "Reload Window"
3. Clean Java workspace: `Ctrl+Shift+P` → "Java: Clean Java Language Server Workspace"

**Eclipse**:
1. Right-click project → `Properties` → `Java Compiler` → `Annotation Processing`
2. Enable project-specific settings
3. Enable annotation processing
4. Clean and rebuild project

### Problem: Method Must Be Abstract

**Error**:
```
error: @RegexPattern method must be abstract
```

**Solution**: Remove method body

```java
// ❌ WRONG: Method has body
@RegexPattern("\\d+")
public ReggieMatcher digits() {
    return null;  // Don't do this!
}

// ✅ CORRECT: Abstract method
@RegexPattern("\\d+")
public abstract ReggieMatcher digits();
```

## Advanced Topics

### Generated Code Structure

Understanding what gets generated helps with debugging:

```
build/generated/sources/annotationProcessor/java/main/
└── com/example/patterns/
    ├── ValidationPatterns$Impl.java        # Implementation class
    ├── PhoneMatcher.java                   # Individual matcher
    ├── EmailMatcher.java
    └── ... (one matcher per pattern)

build/classes/java/main/
└── META-INF/services/
    └── com.example.patterns.ValidationPatterns  # ServiceLoader registration
```

### Performance Characteristics

Compile-time patterns have different performance than runtime:

| Operation | Compile-Time | Runtime | Difference |
|-----------|-------------|---------|------------|
| First compilation | Build time | 5-10ms | Compile-time is free at runtime |
| Pattern instance creation | <1ns (singleton) | <1ns (cached) | Same after warmup |
| Matching | ~10-20x faster than JDK | ~10-20x faster than JDK | Same speed |
| Memory footprint | +50-200KB (classes) | +500KB (ASM lib) | Compile-time lighter |

### GraalVM Native Image Support

Compile-time patterns work perfectly with GraalVM native-image:

```bash
# No special configuration needed!
native-image -jar your-app.jar

# Runtime patterns require reflection config
# Compile-time patterns just work
```

### Pattern Complexity Limits

Reggie chooses strategies based on pattern complexity:

| Pattern Complexity | States | Strategy | Example |
|-------------------|--------|----------|---------|
| Simple | <50 | DFA Unrolled | `hello`, `\d{3}-\d{3}-\d{4}` |
| Medium | 50-500 | DFA Switch | Complex email patterns |
| Complex | >500 | NFA | Very long alternations |
| With assertions | Any | Hybrid DFA+NFA | `(?=.*[A-Z])...` |

For best performance, keep patterns reasonably simple.

### Migration from JDK Pattern

Migrating from `java.util.regex.Pattern`:

**Before** (JDK):
```java
public class Validator {
    private static final Pattern PHONE = Pattern.compile("\\d{3}-\\d{3}-\\d{4}");
    private static final Pattern EMAIL = Pattern.compile("[a-z]+@[a-z]+");

    public boolean isValidPhone(String phone) {
        return PHONE.matcher(phone).matches();
    }

    public boolean isValidEmail(String email) {
        return EMAIL.matcher(email).matches();
    }
}
```

**After** (Reggie):
```java
// 1. Create pattern provider
public abstract class ValidationPatterns implements ReggiePatterns {
    @RegexPattern("\\d{3}-\\d{3}-\\d{4}")
    public abstract ReggieMatcher phone();

    @RegexPattern("[a-z]+@[a-z]+")
    public abstract ReggieMatcher email();
}

// 2. Use in validator
public class Validator {
    private static final ValidationPatterns PATTERNS =
        Reggie.patterns(ValidationPatterns.class);

    public boolean isValidPhone(String phone) {
        return PATTERNS.phone().matches(phone);
    }

    public boolean isValidEmail(String email) {
        return PATTERNS.email().matches(email);
    }
}
```

**Benefits of migration**:
- **10-20x faster** matching
- **Compile-time validation**
- **Zero initialization cost**
- **Same API surface** (`matches()`, `find()`, `findFrom()`)

---

## Next Steps

- Read the [Runtime API Tutorial](TUTORIAL-RUNTIME.md) for dynamic patterns
- Check out [Best Practices](BEST-PRACTICES.md) for advanced usage
- See the [API Reference](../README.md#api-reference) for complete API documentation
- Explore [Performance Tuning](PERFORMANCE-TUNING.md) for optimization tips

## Questions?

- Open an issue on [GitHub](https://github.com/DataDog/java-reggie)
- Check the [FAQ](FAQ.md)
- Read the [main README](../README.md)
