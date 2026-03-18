#!/bin/bash
# Debug Helper for Reggie Development
# Quick access to common debugging workflows

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_header() {
    echo -e "${BLUE}================================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}================================================${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

show_usage() {
    cat <<EOF
Reggie Debug Helper

Usage: $0 <command> [args]

Commands:
  pattern <regex>              Debug pattern compilation and strategy
  test <class>                 Run specific test class
  test-module <module>         Run all tests in a module
  benchmark <pattern>          Run benchmarks matching pattern
  baseline                     Save current benchmark results as baseline
  compare                      Run benchmarks and compare to baseline
  pcre                         Run PCRE conformance tests
  quick-check                  Fast verification (build + core tests)
  full-check                   Full verification (all tests + benchmarks)
  clean                        Clean build and caches

Examples:
  $0 pattern "\d{3}-\d{3}-\d{4}"
  $0 test ReggiMatcherTest
  $0 test-module reggie-runtime
  $0 benchmark ".*Phone.*"
  $0 pcre
  $0 quick-check

For more information, see AGENTS.md
EOF
}

debug_pattern() {
    local pattern="$1"
    if [ -z "$pattern" ]; then
        print_error "Pattern required"
        echo "Usage: $0 pattern \"<regex>\""
        exit 1
    fi

    print_header "Debugging Pattern: $pattern"

    echo ""
    echo "Running pattern analyzer..."
    ./gradlew :reggie-runtime:debugPattern -Ppattern="$pattern" --quiet

    echo ""
    print_success "Pattern analysis complete"
    echo ""
    echo "Check output above for:"
    echo "  - Selected strategy"
    echo "  - NFA/DFA state counts"
    echo "  - Generated methods"
    echo "  - Test results"
}

run_test() {
    local test_class="$1"
    if [ -z "$test_class" ]; then
        print_error "Test class required"
        echo "Usage: $0 test <TestClassName>"
        exit 1
    fi

    print_header "Running Test: $test_class"

    # Try to find which module contains this test
    if ./gradlew :reggie-runtime:test --tests "$test_class" 2>/dev/null; then
        print_success "Runtime tests passed"
    elif ./gradlew :reggie-codegen:test --tests "$test_class" 2>/dev/null; then
        print_success "Codegen tests passed"
    elif ./gradlew :reggie-processor:test --tests "$test_class" 2>/dev/null; then
        print_success "Processor tests passed"
    elif ./gradlew :reggie-integration-tests:test --tests "$test_class" 2>/dev/null; then
        print_success "Integration tests passed"
    else
        print_error "Test not found or failed"
        exit 1
    fi
}

run_module_tests() {
    local module="$1"
    if [ -z "$module" ]; then
        print_error "Module required"
        echo "Usage: $0 test-module <module-name>"
        echo "Modules: reggie-runtime, reggie-codegen, reggie-processor, reggie-integration-tests"
        exit 1
    fi

    print_header "Running Tests: $module"

    if ./gradlew ":$module:test"; then
        print_success "All tests passed"
    else
        print_error "Some tests failed"
        exit 1
    fi
}

run_benchmark() {
    local pattern="$1"
    if [ -z "$pattern" ]; then
        print_error "Benchmark pattern required"
        echo "Usage: $0 benchmark \"<pattern>\""
        echo "Examples:"
        echo "  $0 benchmark \".*Phone.*\""
        echo "  $0 benchmark \"MatchOperation.*\""
        exit 1
    fi

    print_header "Running Benchmarks: $pattern"

    ./gradlew :reggie-benchmark:benchmarkAndReport -Pjmh.args="$pattern"

    print_success "Benchmark complete - check build/reports/benchmark-report.html"
}

save_baseline() {
    print_header "Saving Benchmark Baseline"

    print_warning "This will run ALL benchmarks and may take several minutes..."
    read -p "Continue? (y/n) " -n 1 -r
    echo

    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        print_warning "Cancelled"
        exit 0
    fi

    ./gradlew :reggie-benchmark:benchmarkAndReport
    ./gradlew :reggie-benchmark:saveBaseline

    print_success "Baseline saved - future runs will compare against this"
}

compare_baseline() {
    print_header "Running Benchmarks with Baseline Comparison"

    print_warning "This will run ALL benchmarks and may take several minutes..."

    ./gradlew :reggie-benchmark:benchmarkAndReport

    print_success "Complete - check console output and HTML report for regressions"
}

run_pcre() {
    print_header "Running PCRE Conformance Tests"

    ./gradlew :reggie-integration-tests:test --tests PCRETestSuite

    echo ""
    echo "Current target: 95%+ conformance"
    echo "See doc/plans/pcre-conformance-roadmap.md for details"
}

quick_check() {
    print_header "Quick Verification"

    echo ""
    echo "1. Building project..."
    if ./gradlew build --quiet; then
        print_success "Build passed"
    else
        print_error "Build failed"
        exit 1
    fi

    echo ""
    echo "2. Running core tests..."
    if ./gradlew :reggie-runtime:test :reggie-codegen:test --quiet; then
        print_success "Core tests passed"
    else
        print_error "Core tests failed"
        exit 1
    fi

    echo ""
    print_success "Quick check complete - looks good!"
}

full_check() {
    print_header "Full Verification"

    echo ""
    echo "1. Clean build..."
    ./gradlew clean

    echo ""
    echo "2. Building all modules..."
    if ./gradlew build; then
        print_success "Build passed"
    else
        print_error "Build failed"
        exit 1
    fi

    echo ""
    echo "3. Running integration tests..."
    if ./gradlew :reggie-integration-tests:test; then
        print_success "Integration tests passed"
    else
        print_warning "Some integration tests failed (check PCRE conformance)"
    fi

    echo ""
    echo "4. Quick benchmark smoke test..."
    if ./gradlew :reggie-benchmark:run; then
        print_success "Benchmark smoke test passed"
    else
        print_error "Benchmark smoke test failed"
        exit 1
    fi

    echo ""
    print_success "Full check complete!"
}

clean_all() {
    print_header "Cleaning Build and Caches"

    ./gradlew clean

    # Clear Gradle caches (optional)
    read -p "Clear Gradle caches too? (y/n) " -n 1 -r
    echo

    if [[ $REPLY =~ ^[Yy]$ ]]; then
        rm -rf .gradle/caches/
        print_success "Caches cleared"
    fi

    print_success "Clean complete"
}

# Main command dispatcher
case "${1:-}" in
    pattern)
        debug_pattern "$2"
        ;;
    test)
        run_test "$2"
        ;;
    test-module)
        run_module_tests "$2"
        ;;
    benchmark)
        run_benchmark "$2"
        ;;
    baseline)
        save_baseline
        ;;
    compare)
        compare_baseline
        ;;
    pcre)
        run_pcre
        ;;
    quick-check)
        quick_check
        ;;
    full-check)
        full_check
        ;;
    clean)
        clean_all
        ;;
    help|--help|-h)
        show_usage
        ;;
    "")
        print_error "No command specified"
        echo ""
        show_usage
        exit 1
        ;;
    *)
        print_error "Unknown command: $1"
        echo ""
        show_usage
        exit 1
        ;;
esac
