# Welcome

Welcome! We are glad you are interested in contributing to Reggie. This guide will help you understand the requirements and guidelines to improve your contributor experience.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Contributing to Code](#contributing-to-code)
  - [Available Templates](#available-templates)
  - [Signing Commits](#signing-commits)
  - [New Features](#new-features)
  - [Bug Fixes](#bug-fixes)
  - [Signing the CLA](#signing-the-cla)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Coding Guidelines](#coding-guidelines)
- [Testing Requirements](#testing-requirements)
- [Pull Request Process](#pull-request-process)
- [Contributing to Issues](#contributing-to-issues)
  - [Reporting Security Vulnerabilities](#reporting-security-vulnerabilities)
  - [Reporting Bugs](#reporting-bugs)
  - [Feature Requests](#feature-requests)
  - [PCRE Conformance Issues](#pcre-conformance-issues)
  - [Performance Issues](#performance-issues)
  - [Triaging Issues](#triaging-issues)
- [AI Code Assistants](#ai-code-assistants)
- [Communication](#communication)
- [Recognition](#recognition)
- [License](#license)

## Code of Conduct

This project adheres to the Datadog [Code of Conduct](https://www.datadoghq.com/code-of-conduct/). By participating, you are expected to uphold this code. Please report unacceptable behavior to the project maintainers.

## Contributing to Code

### Available Templates

To streamline contributions, we provide several templates:

**Issue Templates**:
- **[RFC Template](.github/ISSUE_TEMPLATE/rfc.yml)** - For proposing new features or major changes
- **[Bug Report](.github/ISSUE_TEMPLATE/bug_report.yml)** - For reporting bugs
- **[Feature Request](.github/ISSUE_TEMPLATE/feature_request.yml)** - For suggesting enhancements
- **[PCRE Conformance](.github/ISSUE_TEMPLATE/pcre_conformance.yml)** - For PCRE compatibility issues
- **[Performance](.github/ISSUE_TEMPLATE/performance.yml)** - For performance-related issues

**Pull Request Template**:
- **[PR Template](.github/PULL_REQUEST_TEMPLATE.md)** - Automatically loaded when creating PRs

### Signing Commits

Datadog requires all contributors to sign their commits. If you don't currently sign your commits, follow [GitHub's documentation on how to set up your signing keys and start signing your commits](https://docs.github.com/en/authentication/managing-commit-signature-verification/signing-commits).

### New Features

If you want to contribute with a new feature, before starting to write any code, you will need to get your proposal accepted by the maintainers. This is to avoid going through the effort of writing the code and getting it rejected because it is already being worked on in a different way, or it is outside the scope of the project.

**RFC Process**:

1. Open a new issue using the **[RFC template](.github/ISSUE_TEMPLATE/rfc.yml)**
2. Fill in the template to explain:
   - Why you think this feature is needed
   - Why it is useful
   - How you plan to implement it
   - Example use cases
   - Implementation approach and affected modules
   - Testing strategy
3. The maintainers will label the issue as `type/feature` or `type/major_change` and `rfc/discussion`
4. During the RFC process, your change proposal and implementation approaches will be discussed with the maintainers
5. If the proposal gets accepted, it will be tagged as `rfc/approved` - feel free to start coding at that point
6. If the proposal gets rejected, the team will give you an explanation, label the issue as `rfc/rejected` and close it

This ensures you don't waste time with wrong approaches or features that are out of scope for the project.

**Good First Issues**:

Look for issues labeled `good-first-issue` or `help-wanted`. These are great starting points:
- Add test cases for edge cases
- Improve error messages
- Add documentation examples
- Fix typos or formatting

**Areas Needing Help**:

1. **PCRE Conformance** (Current: 94.3%, Target: 95%+)
   - See `doc/plans/pcre-conformance-roadmap.md` for details
   - Pick a failing test from `reggie-integration-tests/`

2. **Performance Optimization**
   - Profile existing patterns
   - Implement specialized generators for common patterns

3. **Documentation**
   - More tutorial examples
   - Architecture deep dives
   - Video explanations

### Bug Fixes

If you have identified an issue that is already labeled as `type/bug` that hasn't been assigned to anyone, feel free to claim it, and ask a maintainer to add you as assignee.

If you've found a new bug, report it using the **[Bug Report template](.github/ISSUE_TEMPLATE/bug_report.yml)** first.

Once you have some code ready, open a PR using the **[PR template](.github/PULL_REQUEST_TEMPLATE.md)**, [linking it to the issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/linking-a-pull-request-to-an-issue#manually-linking-a-pull-request-to-an-issue-using-the-pull-request-sidebar). Take into account that if the changes to fix the bug are not trivial, you need to follow the RFC process as well to discuss the options with the maintainers.

### Signing the CLA

This repository requires you to sign the [Datadog Contributor License Agreement](https://cla.datadoghq.com/). The first time you submit a PR, a bot will walk you through the automated process. On subsequent contributions you will not be prompted unless the content of the agreement has changed.

## Getting Started

### Prerequisites

- Java 21 or higher
- Git
- Gradle 8.11+ (wrapper included)

### First Steps

1. **Fork the repository** on GitHub
2. **Clone your fork**:
   ```bash
   git clone https://github.com/YOUR_USERNAME/reggie.git
   cd reggie
   ```

3. **Build the project**:
   ```bash
   ./gradlew build
   ```

4. **Run tests**:
   ```bash
   ./gradlew test
   ```

5. **Explore the codebase**:
   - Read `README.md` for project overview
   - Review `doc/ARCHITECTURE.md` for system design
   - Check `AGENTS.md` for development workflows

## Development Setup

### Quick Setup

```bash
# Clone and build
git clone https://github.com/YOUR_USERNAME/reggie.git
cd reggie
./gradlew build

# Run quick verification
./scripts/debug-helper.sh quick-check
```

### Development Tools

**Debug Helper Script** - Common workflows:
```bash
./scripts/debug-helper.sh pattern "\d{3}-\d{3}-\d{4}"  # Debug pattern
./scripts/debug-helper.sh test ReggiMatcherTest         # Run test
./scripts/debug-helper.sh benchmark ".*Phone.*"         # Run benchmark
./scripts/debug-helper.sh pcre                          # PCRE conformance
./scripts/debug-helper.sh quick-check                   # Fast verification
```

**IDE Setup**:
- **IntelliJ IDEA**: Import as Gradle project, enable annotation processing
- **VS Code**: Install Java Extension Pack, Gradle for Java
- **Eclipse**: Import as Gradle project

### Module Overview

- `reggie-annotations/`: `@RegexPattern` annotation
- `reggie-codegen/`: Core compilation engine (AST, NFA, DFA, bytecode generation)
- `reggie-processor/`: Annotation processor (compile-time)
- `reggie-runtime/`: Public API + runtime compiler
- `reggie-benchmark/`: JMH benchmarks
- `reggie-integration-tests/`: PCRE/RE2 test suites

## Coding Guidelines

### Code Style

- Follow existing code patterns in the repository
- Use meaningful variable names (avoid single letters except loop counters)
- Keep methods focused and small (<50 lines preferred)
- Document complex bytecode generation with comments

### Java Conventions

```java
// Class names: PascalCase
public class RegexParser { }

// Method names: camelCase
public void parsePattern() { }

// Constants: UPPER_SNAKE_CASE
private static final int MAX_DFA_STATES = 300;

// Variables: camelCase
int stateCount = 0;
```

### Package Structure

- Follow existing package structure
- Place tests in mirrored package structure under `src/test/java/`
- Keep AST nodes in `ast/` package
- Keep bytecode generators in `codegen/` package

### Documentation

- Add JavaDoc to public APIs
- Document why, not what (code shows what)
- Include examples for complex methods
- Keep comments up-to-date with code changes
- **IMPORTANT**: All documentation must be strongly backed by actual code - no hallucinations

## Testing Requirements

### All Code Must Have Tests

**Before submitting a PR**:
1. ✅ All existing tests pass: `./gradlew build`
2. ✅ New code has unit tests
3. ✅ Integration tests updated (if applicable)
4. ✅ Benchmarks added for performance-sensitive changes

### Writing Tests

**Unit Tests** (fast, focused):
```java
@Test
void testDescriptiveName() {
    ReggieMatcher matcher = Reggie.compile("pattern");
    assertTrue(matcher.matches("input"));
    assertFalse(matcher.matches("non-match"));
}
```

**Integration Tests** (correctness):
```java
@ParameterizedTest
@CsvSource({
    "pattern, input, true",
    "pattern, non-match, false"
})
void testPCREConformance(String pattern, String input, boolean expected) {
    ReggieMatcher matcher = Reggie.compile(pattern);
    assertEquals(expected, matcher.matches(input));
}
```

**Benchmark Tests** (performance):
```java
@State(Scope.Thread)
public class MyBenchmark {
    private ReggieMatcher reggie;
    private Pattern jdk;

    @Setup
    public void setup() {
        reggie = Reggie.compile("pattern");
        jdk = Pattern.compile("pattern");
    }

    @Benchmark
    public boolean reggieMatch() {
        return reggie.matches("input");
    }

    @Benchmark
    public boolean jdkMatch() {
        return jdk.matcher("input").matches();
    }
}
```

### Running Tests

```bash
# All tests
./gradlew test

# Specific module
./gradlew :reggie-runtime:test

# Specific test class
./gradlew :reggie-runtime:test --tests ReggiMatcherTest

# Include known failures
./gradlew test -Dreggie.test.knownFailures=true

# PCRE conformance
./gradlew :reggie-integration-tests:test --tests PCRETestSuite
```

### Performance Testing

```bash
# Run benchmarks with baseline comparison
./gradlew :reggie-benchmark:benchmarkAndReport

# Save baseline before changes
./gradlew :reggie-benchmark:saveBaseline

# After changes, compare
./gradlew :reggie-benchmark:benchmarkAndReport
# Check console output for regressions (shown in red)
```

## Pull Request Process

### Before Submitting

1. **Create an issue first** (for non-trivial changes)
   - Discuss approach with maintainers
   - Get feedback before investing time

2. **Branch naming**:
   ```bash
   git checkout -b feature/your-feature-name
   git checkout -b fix/issue-123-description
   git checkout -b docs/improve-readme
   ```

3. **Make your changes**:
   - Follow coding guidelines
   - Add tests
   - Update documentation

4. **Verify locally**:
   ```bash
   ./gradlew build                    # All tests pass
   ./scripts/debug-helper.sh quick-check  # Quick verification
   ```

5. **Commit with clear messages**:
   ```bash
   git commit -m "Add support for possessive quantifiers

   - Implement parsing for *+, ++, ?+, {n,m}+
   - Add PossessiveQuantifierNode AST node
   - Update bytecode generators for possessive mode
   - Add 15 test cases covering edge cases

   Fixes #42"
   ```

### Submitting the PR

1. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

2. **Open Pull Request** on GitHub:
   - Use the **[PR template](.github/PULL_REQUEST_TEMPLATE.md)** (automatically loaded)
   - Clear title describing the change
   - Reference related issues (e.g., `Fixes #123`, `Implements RFC #456`)
   - Check all relevant boxes in the template
   - **IMPORTANT**: Open the PR in **Draft** state initially (per project guidelines)
   - Add the `AI` label if you used AI code assistants

3. **The PR template will guide you through**:
   - Describing what the PR does
   - Linking related issues or RFCs
   - Checking off change types (feature/bug/performance/etc.)
   - Confirming all tests pass
   - Documenting any performance impact
   - Ensuring documentation is updated

### Review Process

1. **Automated checks** must pass:
   - CI build
   - All tests
   - Security scanning

2. **Code review**:
   - Maintainer will review within 1-3 business days
   - Address feedback by pushing new commits
   - Discuss alternative approaches if needed

3. **After approval**:
   - Maintainer will merge
   - PR will be closed automatically
   - Changes will appear in next release

### PR Guidelines

**Do**:
- ✅ Keep PRs focused (one feature/fix per PR)
- ✅ Include tests
- ✅ Update documentation
- ✅ Respond to review comments
- ✅ Squash commits if requested

**Don't**:
- ❌ Mix unrelated changes
- ❌ Submit without testing
- ❌ Ignore CI failures
- ❌ Force-push after review starts (unless requested)
- ❌ Add external dependencies without discussion

## Contributing to Issues

### Reporting Security Vulnerabilities

**IMPORTANT**: If you discover a security vulnerability, please DO NOT open a public issue. Instead, follow the instructions in [SECURITY.md](SECURITY.md) to report it privately using GitHub's private vulnerability reporting feature or via email.

### Reporting Bugs

If you think you have found a bug in Reggie, feel free to report it. When creating a bug report, GitHub will present you with our [bug report template](.github/ISSUE_TEMPLATE/bug_report.yml). Please fill in as much information as possible, including:

- Reggie version and Java version
- Compilation mode (runtime vs compile-time)
- The regex pattern that triggers the bug
- Input string and expected/actual behavior
- Minimal reproduction code

The more details you provide, the faster we can address the issue.

### Feature Requests

For simple feature suggestions (not requiring implementation discussion), use the **[Feature Request template](.github/ISSUE_TEMPLATE/feature_request.yml)**.

For significant features requiring discussion before implementation, use the **[RFC template](.github/ISSUE_TEMPLATE/rfc.yml)** instead.

**The template will guide you to describe**:
- What feature you want
- Why it's useful
- Example use cases
- Potential implementation approach (optional)

### PCRE Conformance Issues

Use the **[PCRE Conformance template](.github/ISSUE_TEMPLATE/pcre_conformance.yml)** to report PCRE compatibility issues.

**The template will guide you to include**:
- Test case from PCRE test suite
- Current behavior vs expected behavior
- Link to PCRE documentation
- Impact on conformance percentage

### Performance Issues

Use the **[Performance template](.github/ISSUE_TEMPLATE/performance.yml)** to report performance regressions or unexpectedly slow patterns.

**The template will guide you to include**:
- The slow regex pattern
- Test input and benchmark results
- Comparison with JDK Pattern performance
- Previous baseline (if regression)
- Debug information from `debugPattern`

### Triaging Issues

Triaging issues is a great way to contribute to an open source project. Some actions you can perform on an issue opened by someone else that will help address it sooner:

- **Trying to reproduce the issue**: If you can reproduce the issue following the steps the reporter provided, add a comment specifying that you could reproduce it
- **Finding duplicates**: If there is a bug, there might be a chance that it was already reported in a different issue. If you find an already reported issue that is the same one as the one you are triaging, add a comment with "Duplicate of" followed by the issue number of the original one
- **Asking the reporter for more information**: Sometimes the reporter of an issue doesn't include enough information to work on the fix, i.e. lack of steps to reproduce, not specifying the affected version, etc. If you find a bug that doesn't have enough information, add a comment tagging the reporter asking for the missing information

## AI Code Assistants

You are welcome to use AI code assistants as part of your contributions, but you should carefully read the rest of the contributing guidelines, as those still apply, regardless of the tools used for the contribution. For example, if you are planning to create a new feature, you should follow the RFC process ([described above](#new-features)) before starting to code.

Also, any contributions, even with the help of AI assistants, should be yours and you are responsible for understanding the project and its codebase and the changes you are making. The maintainers may close your contribution if they suspect that it has been heavily generated by AI with no review or no tests from the contributor.

**AGENTS.md Reference**: This repository includes an [AGENTS.md](AGENTS.md) that your AI code assistant should reference if you are using these tools as part of your contributions. It contains development workflows, build commands, architecture details, and critical rules that AI assistants should follow.

## Development Best Practices

### Critical Rules

1. **Never commit failing tests**
   ```bash
   ./gradlew build  # Must pass before commit
   ```

2. **Dual-path consistency**: Changes to bytecode generation require updates to BOTH:
   - `RuntimeCompiler.java` (runtime path)
   - `ReggiMatcherBytecodeGenerator.java` (compile-time path)

3. **Structural hash completeness**: `PatternInfo.structuralHashCode()` must include ALL fields affecting bytecode

4. **Bytecode generation rules**:
   - Never hardcode local variable slots
   - Never use `visitLdcInsn` with primitive int (use `BytecodeUtil.pushInt`)
   - Document slot allocation

### Debugging

```bash
# Inspect pattern compilation
./gradlew :reggie-runtime:debugPattern -Ppattern="your-pattern"

# Profile performance
./gradlew :reggie-benchmark:jmh -Djmh.args="YourBenchmark -prof async:event=alloc"

# Compare with JDK
Pattern jdk = Pattern.compile("pattern");
ReggieMatcher reggie = Reggie.compile("pattern");
System.out.println("JDK: " + jdk.matcher(input).matches());
System.out.println("Reggie: " + reggie.matches(input));
```

## Communication

### Asking Questions

**Before asking**:
1. Check existing documentation
2. Search closed issues
3. Review `AGENTS.md` for workflows

**Where to ask**:
- GitHub Issues: For bugs, features, PCRE conformance
- GitHub Discussions: For general questions, architecture discussions
- Pull Request comments: For PR-specific questions

### Getting Help

**Stuck?**
- Review similar patterns in test suites
- Check existing bytecode generators for examples
- Ask in GitHub Discussions
- Tag maintainers in issues (use sparingly)

## Recognition

Contributors will be:
- Listed in release notes
- Mentioned in commits (Co-Authored-By)
- Credited in project documentation

Significant contributions may be highlighted in:
- Project README
- Blog posts
- Conference talks

## License

By contributing to Reggie, you agree that your contributions will be licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

All contributions must include the Datadog copyright header:

```java
// Unless explicitly stated otherwise all files in this repository are licensed
// under the Apache License Version 2.0.
// This product includes software developed at Datadog (https://www.datadoghq.com/).
// Copyright 2026-Present Datadog, Inc.
```

## Additional Resources

- [README.md](README.md) - Project overview and usage
- [SECURITY.md](SECURITY.md) - Security policy and vulnerability reporting
- [ARCHITECTURE.md](doc/ARCHITECTURE.md) - System design
- [AGENTS.md](AGENTS.md) - Development workflows
- [PCRE Conformance Roadmap](doc/plans/pcre-conformance-roadmap.md) - Conformance tracking

## Questions?

Feel free to open an issue or start a discussion on GitHub. We're here to help!

---

**Thank you for contributing to Reggie!** 🎉
