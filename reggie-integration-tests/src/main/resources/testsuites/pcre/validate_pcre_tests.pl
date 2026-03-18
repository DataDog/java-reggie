#!/usr/bin/perl
#
# PCRE Test Validator
# ====================
# Validates PCRE test cases against actual Perl regex implementation.
#
# Usage:
#   # Validate tests and show errors:
#   perl validate_pcre_tests.pl < pcre-capturing-groups.txt
#
#   # Generate corrected test file:
#   perl validate_pcre_tests.pl --fix < pcre-capturing-groups.txt > fixed-tests.txt
#
# Test File Format:
#   pattern;input;group1Value;group2Value;...
#
# Notes:
#   - Patterns in the test file use \\ to represent a single backslash
#   - Input strings can use escape sequences: \n, \r, \t, \x0a (hex)
#   - Lines starting with # are comments (preserved in --fix mode)
#   - Patterns that don't compile are marked with # PATTERN_ERROR
#   - Patterns that don't match in Perl are marked with # NO_MATCH
#   - ALL tests are kept - nothing is removed
#
# Example:
#   Run validation:
#     perl validate_pcre_tests.pl < pcre-capturing-groups.txt
#
#   Regenerate with corrections (then review the diff!):
#     perl validate_pcre_tests.pl --fix < pcre-capturing-groups.txt > /tmp/fixed.txt
#     diff -u pcre-capturing-groups.txt /tmp/fixed.txt
#     cp /tmp/fixed.txt pcre-capturing-groups.txt
#

use strict;
use warnings;

my $fix_mode = 0;
if (@ARGV && $ARGV[0] eq '--fix') {
    $fix_mode = 1;
}

my $line_num = 0;
my @errors;
my @fixed_lines;

# Convert test file escapes to actual characters
# Test file uses \\ to represent a single backslash
sub unescape_pattern {
    my ($s) = @_;
    # Replace \\\\ with a placeholder first
    $s =~ s/\\\\/\x00BSLASH\x00/g;
    # Now \x00BSLASH\x00 represents a literal backslash in the pattern
    $s =~ s/\x00BSLASH\x00/\\/g;
    return $s;
}

# Convert test file input escapes to actual characters
sub unescape_input {
    my ($s) = @_;
    # Replace \\\\ with a placeholder first
    $s =~ s/\\\\/\x00BSLASH\x00/g;
    # Handle hex escapes
    $s =~ s/\\x([0-9a-fA-F]{2})/chr(hex($1))/ge;
    # Handle standard escapes
    $s =~ s/\\n/\n/g;
    $s =~ s/\\r/\r/g;
    $s =~ s/\\t/\t/g;
    # Restore literal backslashes
    $s =~ s/\x00BSLASH\x00/\\/g;
    return $s;
}

# Escape a captured value back to test file format
sub escape_value {
    my ($s) = @_;
    return '' unless defined $s;
    # Use \\x0a format to be consistent with original test file format
    $s =~ s/\n/\\x0a/g;
    $s =~ s/\r/\\x0d/g;
    $s =~ s/\t/\\x09/g;
    return $s;
}

while (my $line = <STDIN>) {
    $line_num++;
    chomp $line;

    # Preserve comments and empty lines
    if ($line =~ /^#/ || $line =~ /^\s*$/) {
        push @fixed_lines, $line;
        next;
    }

    # Parse test line: pattern;input;group1;group2;...
    my @parts = split /;/, $line, -1;  # -1 to keep trailing empty strings
    if (@parts < 2) {
        push @fixed_lines, $line;
        next;
    }

    my $raw_pattern = $parts[0];
    my $raw_input = $parts[1];
    my @expected_groups = @parts[2..$#parts] if @parts > 2;

    # Convert escapes
    my $pattern = unescape_pattern($raw_pattern);
    my $test_input = unescape_input($raw_input);

    # Try to match
    my @actual_groups;
    my $matched = 0;

    eval {
        if ($test_input =~ /$pattern/) {
            $matched = 1;
            # Capture groups - need to get all numbered groups
            no strict 'refs';
            for my $i (1..20) {  # Check up to 20 groups
                my $val = ${$i};
                last unless defined $val || $i <= scalar(@expected_groups);
                push @actual_groups, $val // '';
            }
            # Trim trailing empty groups to match expected count
            while (@actual_groups && @actual_groups > @expected_groups && $actual_groups[-1] eq '') {
                pop @actual_groups;
            }
        }
    };

    if ($@) {
        # Pattern error - keep the test but mark it
        my $err = $@;
        $err =~ s/\n.*//s;  # First line only
        print STDERR "ERROR at line $line_num: $err\n" unless $fix_mode;
        if ($fix_mode) {
            push @fixed_lines, "$line # PATTERN_ERROR";
        } else {
            push @fixed_lines, $line;
        }
        next;
    }

    # Compare results
    my $ok = 1;
    my $error_msg = "";

    if (!$matched && @expected_groups > 0) {
        $ok = 0;
        $error_msg = "NO_MATCH: expected match with groups but got no match";
    } elsif ($matched && @expected_groups == 0 && @parts > 2) {
        # Special case: test expects empty groups (which means match with no captured content)
        # This is fine
    } elsif ($matched) {
        # Check if groups match
        for my $i (0..$#expected_groups) {
            my $exp = $expected_groups[$i] // '';
            my $act = $actual_groups[$i] // '';

            # Handle escape sequences in expected values
            my $exp_decoded = unescape_input($exp);

            if ($exp_decoded ne $act) {
                $ok = 0;
                $error_msg = "GROUP MISMATCH: group[" . ($i+1) . "] expected '$exp' but got '" . escape_value($act) . "'";
                last;
            }
        }
    }

    if ($ok) {
        push @fixed_lines, $line;
    } else {
        push @errors, "Line $line_num: $error_msg\n  Pattern: $raw_pattern\n  Input: $raw_input\n  Expected: " . join(',', @expected_groups) . "\n  Actual: " . join(',', map { escape_value($_) } @actual_groups);

        if ($fix_mode) {
            if ($matched) {
                # Generate corrected line with actual captured groups
                my @escaped_groups = map { escape_value($_) } @actual_groups;
                push @fixed_lines, "$raw_pattern;$raw_input;" . join(';', @escaped_groups);
            } else {
                # No match - keep the test but mark it as no-match
                # Output with empty groups section to indicate no match
                push @fixed_lines, "$raw_pattern;$raw_input; # NO_MATCH";
            }
        } else {
            push @fixed_lines, $line;
        }
    }
}

if ($fix_mode) {
    print "$_\n" for @fixed_lines;
} else {
    if (@errors) {
        print "Found " . scalar(@errors) . " errors:\n\n";
        print "$_\n\n" for @errors;
    } else {
        print "All $line_num tests passed!\n";
    }
}
