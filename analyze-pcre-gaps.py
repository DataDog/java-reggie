#!/usr/bin/env python3
"""
Analyze PCRE test failures to identify missing features.
"""

import re
from collections import defaultdict

# Read the PCRE capturing groups test file
pcre_file = "reggie-integration-tests/src/main/resources/testsuites/pcre/pcre-capturing-groups.txt"

feature_patterns = {
    "inline_modifiers": r"\(\?[imsxadlu-]+\)",  # (?i), (?m), (?s), (?x), (?-i), etc.
    "inline_modifiers_scoped": r"\(\?[imsxadlu-]+:",  # (?i:...)
    "named_groups": r"\(\?['\<]",  # (?'name'...) or (?<name>...)
    "subroutines": r"\(\?\d+\)|\(\?R\)|\(\?&",  # (?1), (?R), (?&name)
    "conditionals": r"\(\?\(\d+\)",  # (?(1)yes|no)
    "backtracking_verbs": r"\(\*[A-Z]+:",  # (*MARK:), (*PRUNE:), (*SKIP:), (*THEN:)
    "keep_out": r"\\K",  # \K - reset start of match
    "relative_backrefs": r"\\g\{",  # \g{-1}, \g{1}
    "comments": r"\(\?#",  # (?#comment)
    "atomic_groups": r"\(\?>",  # (?>...)
    "possessive_quantifiers": r"[*+?]\+",  # *+, ++, ?+
    "lookahead": r"\(\?=|\(\?!",  # (?=...), (?!...)
    "lookbehind": r"\(\?<=|\(\?<!",  # (?<=...), (?<!...)
    "backreferences": r"\\[1-9]",  # \1, \2, etc.
    "unicode_props": r"\\p\{",  # \p{L}, \p{N}, etc.
}

features_needed = defaultdict(list)

try:
    with open(pcre_file, 'r', encoding='utf-8') as f:
        line_num = 0
        for line in f:
            line_num += 1
            line = line.strip()
            if not line or line.startswith('#'):
                continue

            # Extract pattern (first field before ;)
            parts = line.split(';')
            if len(parts) < 2:
                continue

            pattern = parts[0]

            # Check which features are used
            for feature, regex in feature_patterns.items():
                if re.search(regex, pattern):
                    features_needed[feature].append((line_num, pattern))

    print("=== PCRE Feature Gap Analysis ===\n")
    print(f"Total patterns analyzed: {line_num}\n")

    # Sort by frequency
    sorted_features = sorted(features_needed.items(), key=lambda x: len(x[1]), reverse=True)

    for feature, patterns in sorted_features:
        print(f"\n{feature}: {len(patterns)} patterns")
        print("-" * 60)
        # Show first 5 examples
        for line_num, pattern in patterns[:5]:
            print(f"  Line {line_num}: {pattern[:80]}")
        if len(patterns) > 5:
            print(f"  ... and {len(patterns) - 5} more")

    print("\n\n=== Summary ===")
    print(f"Total unique features needed: {len(features_needed)}")
    print("\nTop 5 most needed features:")
    for feature, patterns in sorted_features[:5]:
        print(f"  {feature}: {len(patterns)} patterns ({len(patterns)*100//line_num}%)")

except FileNotFoundError:
    print(f"File not found: {pcre_file}")
except Exception as e:
    print(f"Error: {e}")
