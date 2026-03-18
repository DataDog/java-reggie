#!/usr/bin/env python3
"""
Update README.md with latest benchmark results from JMH JSON output.
Parses JMH results and formats them into a markdown table between markers.
"""

import json
import sys
import re
from datetime import datetime
from pathlib import Path

def parse_jmh_results(json_file):
    """Parse JMH results JSON and extract key metrics."""
    with open(json_file, 'r') as f:
        data = json.load(f)

    benchmarks = []
    for result in data:
        benchmark_name = result['benchmark']
        # Extract just the method name
        method_name = benchmark_name.split('.')[-1]

        # Get score and unit
        score = result['primaryMetric']['score']
        unit = result['primaryMetric']['scoreUnit']

        # Parse params to identify pattern type
        params = result.get('params', {})
        pattern_type = params.get('pattern', 'unknown')

        benchmarks.append({
            'name': method_name,
            'pattern': pattern_type,
            'score': score,
            'unit': unit,
            'mode': result['mode']
        })

    return benchmarks

def calculate_speedup(benchmarks):
    """Calculate Reggie vs JDK speedup from benchmark pairs."""
    results = {}

    # Group by benchmark name (stripping _reggie/_jdk suffix)
    by_base_name = {}
    for b in benchmarks:
        # Extract base name (e.g., "phoneMatch" from "phoneMatch_reggie")
        base_name = b['name'].replace('_reggie', '').replace('_jdk', '').replace('Reggie', '').replace('JDK', '')

        if base_name not in by_base_name:
            by_base_name[base_name] = []
        by_base_name[base_name].append(b)

    # Calculate speedups
    for base_name, benches in by_base_name.items():
        jdk_score = None
        reggie_score = None

        for b in benches:
            name_lower = b['name'].lower()
            if 'jdk' in name_lower:
                jdk_score = b['score']
            elif 'reggie' in name_lower:
                reggie_score = b['score']

        if jdk_score and reggie_score and jdk_score > 0:
            # Higher score is better for throughput (ops/ms)
            speedup = reggie_score / jdk_score

            # Use base name as display name, clean it up
            display_name = base_name.replace('_', ' ').title()

            results[display_name] = {
                'reggie': reggie_score,
                'jdk': jdk_score,
                'speedup': speedup,
                'unit': benches[0]['unit']
            }

    return results

def format_benchmark_table(results):
    """Format benchmark results as markdown table."""
    if not results:
        return "*No benchmark data available*"

    lines = []
    lines.append("| Benchmark | Reggie (ops/ms) | JDK (ops/ms) | Speedup |")
    lines.append("|-----------|-----------------|--------------|---------|")

    # Sort by speedup (highest first)
    sorted_results = sorted(results.items(), key=lambda x: x[1]['speedup'], reverse=True)

    for pattern, data in sorted_results:
        # Format numbers with thousands separator
        reggie_val = f"{data['reggie']:,.0f}"
        jdk_val = f"{data['jdk']:,.0f}"
        speedup = f"**{data['speedup']:.2f}x**"

        lines.append(f"| {pattern} | {reggie_val} | {jdk_val} | {speedup} |")

    # Add summary
    avg_speedup = sum(r['speedup'] for r in results.values()) / len(results)
    total_benchmarks = len(results)
    reggie_wins = sum(1 for r in results.values() if r['speedup'] > 1.0)

    lines.append("")
    lines.append(f"**Summary**: {total_benchmarks} benchmarks • Average speedup: **{avg_speedup:.2f}x** • Reggie wins: {reggie_wins}/{total_benchmarks}")
    lines.append("")
    lines.append(f"*Last updated: {datetime.now().strftime('%Y-%m-%d %H:%M UTC')} (auto-updated on merge to main)*")

    return "\n".join(lines)

def update_readme(readme_file, benchmark_content):
    """Update README.md between markers with new benchmark content."""
    with open(readme_file, 'r') as f:
        content = f.read()

    # Pattern to match content between markers
    pattern = r'(<!-- BENCHMARK_RESULTS_START -->)(.*?)(<!-- BENCHMARK_RESULTS_END -->)'

    replacement = f'\\1\n{benchmark_content}\n\\3'

    updated_content = re.sub(pattern, replacement, content, flags=re.DOTALL)

    with open(readme_file, 'w') as f:
        f.write(updated_content)

    print(f"✓ Updated {readme_file}")

def main():
    if len(sys.argv) < 2:
        print("Usage: python update-benchmark-readme.py <jmh-results.json>")
        sys.exit(1)

    json_file = Path(sys.argv[1])
    readme_file = Path(__file__).parent.parent / 'README.md'

    if not json_file.exists():
        print(f"Error: {json_file} not found")
        sys.exit(1)

    if not readme_file.exists():
        print(f"Error: {readme_file} not found")
        sys.exit(1)

    print(f"Parsing benchmark results from {json_file}...")
    benchmarks = parse_jmh_results(json_file)

    print(f"Found {len(benchmarks)} benchmark results")

    print("Calculating speedups...")
    results = calculate_speedup(benchmarks)

    print(f"Processed {len(results)} pattern comparisons")

    print("Formatting table...")
    table = format_benchmark_table(results)

    print("Updating README...")
    update_readme(readme_file, table)

    print("\n✓ Done! README.md updated with latest benchmark results")
    print("\nPreview:")
    print(table)

if __name__ == '__main__':
    main()
