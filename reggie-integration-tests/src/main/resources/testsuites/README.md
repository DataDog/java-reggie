# Test Suite Data Extraction

This directory contains test patterns extracted from industry-standard regex test suites (RE2 and PCRE) specifically for testing capturing groups and replacement functionality in Reggie.

## Test Files

### Capturing Groups

#### RE2 Capturing Groups (`re2/re2-capturing-groups.txt`)
- **Source**: RE2 test suite (re2-search.txt)
- **Tests extracted**: 92 tests with capturing groups (including backreferences)
- **Format**: `pattern;input;group1Start-group1End;group2Start-group2End;...`
- **Example**: `(a+|b)+;ab;1-2` - Pattern `(a+|b)+` on input `ab` captures group 1 at positions 1-2

The position format uses byte offsets (start-end) where:
- Group 0 (full match) is not included in the output
- Groups are numbered 1, 2, 3, etc.
- Format: `startPosition-endPosition` (exclusive end)

#### RE2 Basic Patterns (`re2/re2-basic.txt`)
- **Source**: RE2 test suite patterns
- **Tests extracted**: 95 basic match/no-match tests
- **Format**: `pattern;input;should_match;features`
- **Example**: `abc;abc;true;literal` - Pattern `abc` matches input `abc`

#### PCRE Capturing Groups (`pcre/pcre-capturing-groups.txt`)
- **Source**: PCRE2 test suite (testinput1/testoutput1)
- **Tests extracted**: 386 tests with capturing groups (including backreferences)
- **Format**: `pattern;input;group1Value;group2Value;...`
- **Example**: `a(b*);abbbb;bbbb` - Pattern `a(b*)` on input `abbbb` captures "bbbb" in group 1

The value format directly includes the captured string:
- Group 0 (full match) is not included
- Groups are numbered 1, 2, 3, etc.
- Empty captures are represented as empty fields between semicolons

### Basic Pattern Matching

#### PCRE Patterns (`pcre/pcre-patterns.txt`)
- **Source**: Hand-curated patterns focused on performance-relevant and common real-world use cases (emails, URLs, etc.)
- **Tests extracted**: 55 basic match/no-match tests
- **Format**: `pattern;input;should_match;features`
- **Example**: `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,};test@example.com;true;character_class,quantifier`

#### Common Patterns (`common/patterns.json`)
- **Source**: Hand-curated patterns shared across test suites
- **Format**: JSON (not semicolon-delimited) — a top-level `patterns` array where each entry has `name`, `pattern`, `features`, and a nested `testCases` array of `{input, shouldMatch}` objects
- **Example**:
  ```json
  {
    "name": "email_simple",
    "pattern": "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
    "features": ["character_class", "quantifier"],
    "testCases": [
      {"input": "test@example.com", "shouldMatch": true}
    ]
  }
  ```

### Replacements

#### PCRE Replacements (`pcre/pcre-replacements.txt`)
- **Source**: Hand-curated test cases covering common substitution patterns (not extracted from PCRE2 testinput12)
- **Tests extracted**: 38 replacement/substitution tests
- **Format**: `pattern;input;replacement;expectedOutput;global`
- **Example**: `(?<=abc)(|def);123abcáyz;$0;;true` - Replace pattern with `$0` (matched text)

Format details:
- `pattern`: Regular expression pattern
- `input`: Input string to search/replace
- `replacement`: Replacement string (supports `$0`, `$1`, `$2`, etc. for group references)
- `expectedOutput`: Expected result after replacement (may be empty if not extracted)
- `global`: `true` for replaceAll, `false` for replaceFirst

## Data Extraction Process

The test data was extracted using custom parsers:

1. **RE2CaptureGroupParser**: Parses RE2's semicolon-delimited format with position ranges
2. **PCRECaptureGroupParser**: Parses PCRE's input/output file pairs to extract pattern, input, and captured values
3. **PCREReplacementParser**: Extracts replacement tests with the `replace=` modifier

### Validation

- **`pcre/validate_pcre_tests.pl`**: Perl script that validates `pcre-capturing-groups.txt`-style test cases against Perl's own regex engine, e.g. `perl validate_pcre_tests.pl < pcre-capturing-groups.txt`

### Filtering

Tests are filtered to include only those supported by Reggie:
- ✅ Regular capturing groups `(pattern)`
- ✅ Non-capturing groups `(?:pattern)`
- ✅ Quantifiers `+`, `*`, `?`, `{n,m}`
- ✅ Alternation `|`
- ✅ Anchors `^`, `$`
- ✅ Backreferences `\1`, `\2`, etc. (supported via NFA)
- ✅ Lookahead and lookbehind assertions
- ✅ Word boundaries `\b`, `\B`
- ❌ Named groups `(?P<name>)` (not yet supported)
- ❌ Conditional patterns `(?(condition)yes|no)` (not supported)
- ❌ Atomic groups `(?>...)` (not supported)
- ❌ Possessive quantifiers `*+`, `++`, `?+` (not supported)
- ❌ Unicode properties `\p{L}`, `\P{N}` (not supported)
- ✅ Inline flags `(?i)`, `(?m)`, `(?s)`, `(?x)` (global and scoped)
- ❌ PCRE verbs `(*ACCEPT)`, `(*COMMIT)`, `(*THEN)` (not supported)
- ❌ Case conversion in replacements `\U`, `\L` (not supported)

## Test File Format

Most test files use semicolon-delimited format; `common/patterns.json` uses a JSON structure instead:

```
# Comment lines start with #
pattern;input;expectedData1;expectedData2;...
```

### Escaping

Special characters are escaped in the format:
- `\\` - Backslash
- `\;` - Semicolon
- `\n` - Newline
- `\r` - Carriage return
- `\t` - Tab

## Usage

These test files can be used to:
1. Validate capturing group extraction correctness
2. Test replacement/substitution functionality
3. Benchmark performance of group capture operations
4. Ensure compatibility with RE2 and PCRE behavior

## Regenerating Test Data

To regenerate the test files:

```bash
# Download source test files
curl -o /tmp/re2-search.txt https://raw.githubusercontent.com/google/re2j/master/testdata/re2-search.txt
curl -o /tmp/pcre-testinput1.txt https://raw.githubusercontent.com/PCRE2Project/pcre2/master/testdata/testinput1
curl -o /tmp/pcre-testoutput1.txt https://raw.githubusercontent.com/PCRE2Project/pcre2/master/testdata/testoutput1
curl -o /tmp/pcre-testinput12.txt https://raw.githubusercontent.com/PCRE2Project/pcre2/master/testdata/testinput12

# Compile and run extractor
./gradlew :reggie-integration-tests:compileJava
cd reggie-integration-tests
java -cp build/classes/java/main com.datadoghq.reggie.integration.testdata.TestDataExtractor \
  ../reggie-integration-tests/src/main/resources/testsuites
```

## Statistics

- **Total tests**: 516 tests extracted (including backreference tests)
  - RE2 capturing groups: 92 tests
  - PCRE capturing groups: 386 tests
  - PCRE replacements: 38 tests

Tests filtered out: 66 tests using unsupported PCRE-specific features (verbs, atomic groups, etc.)

## Related Classes

- `CaptureGroupTest` - Test case with expected captures
- `ReplacementTest` - Replacement/substitution test case
- `GroupCapture` - Individual group capture expectation
- `RE2CaptureGroupParser` - Parser for RE2 format
- `PCRECaptureGroupParser` - Parser for PCRE capture format
- `PCREReplacementParser` - Parser for PCRE replacement format
- `TestDataExtractor` - Main extraction orchestrator
