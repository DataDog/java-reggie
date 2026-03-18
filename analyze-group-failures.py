#!/usr/bin/env python3
"""
Analyze group extraction failures from PCRE test output.
"""

import re
from collections import defaultdict

# Sample failure patterns from the test output
failures = """
Pattern '^(a?)+b' on 'b': Group 1: expected '', got 'null'
Pattern '^(a?)+b' on 'ab': Group 1: expected '', got 'a'
Pattern 'a(b*)' on 'a': Group 1: expected '', got 'a'
Pattern 'a([bc]*)c*' on 'abc': Group 1: expected 'a', got 'bc'
Pattern '([^ab]*)*' on 'cccc': Group 1: expected '', got 'c'
Pattern '([a-c]*)\1' on 'abcabc': Group 1: expected 'abc', got 'null'
Pattern '(a+|b)+' should match 'AB' but didn't
Pattern 'a(b*)' on 'a': Group 1: expected '', got 'a'
Pattern '^(a|)\1+b' should match 'aab' but didn't
Pattern '(a)\1{8,}' on 'aaaaaaaaa': Group 1: expected 'a', got 'null'
Pattern '([ab]*)*' on 'a': Group 1: expected '', got 'a'
Pattern '([ab]*)*' on 'ababab': Group 1: expected '', got 'b'
Pattern '([a]*)*' on 'a': Group 1: expected '', got 'a'
Pattern '^(abc){1,2}zz' on 'abczz': Group 1: expected 'abc', got 'c'
Pattern '(.*?)(\d+)' on 'I have 2 numbers: 53147': Group 1: expected '', got 'I have 2 numbers: 53147'
"""

issue_categories = defaultdict(list)

# Categorize issues
patterns = [
    (r"expected '', got 'null'", "empty_group_returns_null",
     "Empty group capture should return empty string, not null"),

    (r"expected '', got '([^']+)'", "empty_group_wrong_value",
     "Empty group should return empty string but returns actual value"),

    (r"expected '([^']+)', got 'null'", "nonempty_group_returns_null",
     "Non-empty group capture returns null instead of value"),

    (r"expected '([^']+)', got '([^']+)'", "wrong_group_value",
     "Group captures wrong value"),

    (r"should match '([^']+)' but didn't", "match_failure",
     "Pattern should match but doesn't (likely backreference issue)"),
]

for line in failures.split('\n'):
    if not line.strip():
        continue

    categorized = False
    for pattern, category, desc in patterns:
        if re.search(pattern, line):
            issue_categories[category].append(line.strip())
            categorized = True
            break

    if not categorized:
        issue_categories["other"].append(line.strip())

print("=== Group Extraction Issue Categories ===\n")

for category, desc in [(p[1], p[2]) for p in patterns]:
    if category in issue_categories:
        issues = issue_categories[category]
        print(f"\n{category.upper().replace('_', ' ')}")
        print(f"Description: {desc}")
        print(f"Count: {len(issues)}")
        print("-" * 70)
        for issue in issues[:3]:
            print(f"  {issue}")
        if len(issues) > 3:
            print(f"  ... and {len(issues) - 3} more")

if "other" in issue_categories:
    print(f"\n\nOTHER ISSUES: {len(issue_categories['other'])}")
    for issue in issue_categories["other"][:5]:
        print(f"  {issue}")

print("\n\n=== Root Cause Analysis ===\n")

print("""
1. EMPTY GROUP RETURNS NULL (Critical)
   - Pattern: (a?)+ or (a*)*
   - Issue: When group matches empty string, returns null instead of ""
   - Root cause: Group boundary tracking not handling empty matches
   - Fix: In NFA group tracking, set group bounds even for zero-width matches

2. QUANTIFIED GROUPS - LAST ITERATION
   - Pattern: (a+|b)+ on "AB"
   - Issue: Should capture "B" (last iteration) but fails to match
   - Root cause: Quantified groups need to update capture on each iteration
   - Fix: Track group captures through quantifier iterations

3. BACKREFERENCE PATTERN FAILURES
   - Pattern: (a)\1{8,} or (a|)\1+b
   - Issue: Pattern with backreference doesn't match when it should
   - Root cause: Backreference not seeing captured group value
   - Fix: Ensure NFA state carries group values for backreference matching

4. WRONG GROUP VALUE
   - Pattern: a(b*) on "a" expects b* = "", but gets "a"
   - Issue: Capturing wrong part of the match
   - Root cause: Group boundaries not correctly tracked in DFA/NFA hybrid
   - Fix: Verify group start/end positions during match

5. NESTED GROUPS
   - Pattern: ^(?:a(b(c)))(?:d(e(f)))...
   - Issue: Nested groups return wrong values or are off-by-one
   - Root cause: Group numbering or tracking in nested contexts
   - Fix: Verify group number assignment and tracking in nested structures
""")

print("\n=== Recommended Action Plan ===\n")
print("""
Priority 1: Fix empty group handling
- Ensure group tracking distinguishes between "not matched" and "matched empty"
- Test: (a?)+ on "b" should give group 1 = ""

Priority 2: Fix quantified group iteration tracking
- Track group updates through each iteration of quantifier
- Test: (a+|b)+ on "AB" should match with group 1 = "B"

Priority 3: Fix backreference integration
- Ensure captured values are available for backreference matching
- Test: (a)\1{8,} on "aaaaaaaaa" should match with group 1 = "a"

Priority 4: Verify group boundary tracking in hybrid DFA/NFA
- Review NFABytecodeGenerator group tracking logic
- Test: a(b*) on "a" should give group 1 = ""
""")
