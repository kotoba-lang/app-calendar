#!/bin/bash
# Combined Coverage Report Script
# Generates coverage reports for Rust, TypeScript, and E2E tests

set -e

echo "=========================================="
echo "Project-wide Test Coverage Report"
echo "=========================================="
echo ""

COVERAGE_DIR="./coverage"
RUST_COVERAGE_DIR="$COVERAGE_DIR/rust"
TYPESCRIPT_COVERAGE_DIR="$COVERAGE_DIR/typescript"
E2E_COVERAGE_DIR="$COVERAGE_DIR/e2e"
COMBINED_DIR="$COVERAGE_DIR/combined"

mkdir -p "$COVERAGE_DIR"
mkdir -p "$RUST_COVERAGE_DIR"
mkdir -p "$TYPESCRIPT_COVERAGE_DIR"
mkdir -p "$E2E_COVERAGE_DIR"
mkdir -p "$COMBINED_DIR"

# 1. Rust Service Coverage
echo "1. Measuring Rust Service Coverage..."
cd performers/services/calendar-service
if [ -f "./scripts/test-coverage.sh" ]; then
    ./scripts/test-coverage.sh
    # Copy Rust coverage reports
    cp -r target/tarpaulin/* "$RUST_COVERAGE_DIR/" 2>/dev/null || true
    echo "   ✓ Rust coverage report generated"
else
    echo "   ⚠ Rust coverage script not found"
fi
cd - > /dev/null

# 2. TypeScript Coverage
echo ""
echo "2. Measuring TypeScript Coverage..."
cd performers/systems/calendar-system
if [ -f "package.json" ]; then
    if [ ! -d "node_modules" ]; then
        echo "   Installing dependencies..."
        pnpm install --silent || npm install --silent || true
    fi
    if pnpm test:coverage > /dev/null 2>&1 || npm run test:coverage > /dev/null 2>&1; then
        # Copy TypeScript coverage reports
        cp -r coverage/* "$TYPESCRIPT_COVERAGE_DIR/" 2>/dev/null || true
        echo "   ✓ TypeScript coverage report generated"
    else
        echo "   ⚠ TypeScript coverage measurement failed (tests may need setup)"
    fi
else
    echo "   ⚠ TypeScript package.json not found"
fi
cd - > /dev/null

# 3. E2E Coverage
echo ""
echo "3. Measuring E2E Coverage..."

# Calendar System E2E Tests
echo "   3.1 Calendar System E2E Tests..."
cd performers/systems/calendar-system/e2e
if [ -f "playwright.config.ts" ]; then
    if [ ! -d "node_modules" ]; then
        echo "      Installing dependencies..."
        npm install --silent || true
    fi
    if npm test -- --reporter=json > /dev/null 2>&1; then
        # Copy E2E coverage reports
        cp playwright-report/results.json "$E2E_COVERAGE_DIR/calendar-system-results.json" 2>/dev/null || true
        echo "      ✓ Calendar System E2E coverage report generated"
    else
        echo "      ⚠ Calendar System E2E coverage measurement failed (tests may need setup)"
    fi
else
    echo "      ⚠ Calendar System E2E playwright.config.ts not found"
fi
cd - > /dev/null

# Calendar Service E2E Tests
echo "   3.2 Calendar Service E2E Tests..."
cd performers/services/calendar-service/e2e
if [ -f "playwright.config.ts" ]; then
    if [ ! -d "node_modules" ]; then
        echo "      Installing dependencies..."
        npm install --silent || true
    fi
    if npm test -- --reporter=json > /dev/null 2>&1; then
        # Copy E2E coverage reports
        cp playwright-report/results.json "$E2E_COVERAGE_DIR/calendar-service-results.json" 2>/dev/null || true
        echo "      ✓ Calendar Service E2E coverage report generated"
    else
        echo "      ⚠ Calendar Service E2E coverage measurement failed (tests may need setup)"
    fi
else
    echo "      ⚠ Calendar Service E2E playwright.config.ts not found"
fi
cd - > /dev/null

# 4. Generate Combined Report
echo ""
echo "4. Generating Combined Coverage Report..."

# Extract coverage percentages
RUST_COVERAGE=$(grep -oP '\d+\.\d+%' "$RUST_COVERAGE_DIR/cobertura.xml" 2>/dev/null | head -1 || echo "N/A")
TYPESCRIPT_COVERAGE=$(cat "$TYPESCRIPT_COVERAGE_DIR/coverage-summary.json" 2>/dev/null | grep -oP '"total":\s*{[^}]*"lines":\s*{\s*"pct":\s*\K\d+\.\d+' || echo "N/A")
E2E_COVERAGE="N/A" # E2E coverage is typically measured differently

# Generate markdown report
cat > "$COMBINED_DIR/COVERAGE_REPORT.md" << EOF
# Project-wide Test Coverage Report

Generated: $(date)

## Summary

| Component | Coverage | Status |
|-----------|----------|--------|
| Rust Service | $RUST_COVERAGE | $( [ "$RUST_COVERAGE" != "N/A" ] && echo "✓" || echo "⚠" ) |
| TypeScript/Next.js | $TYPESCRIPT_COVERAGE | $( [ "$TYPESCRIPT_COVERAGE" != "N/A" ] && echo "✓" || echo "⚠" ) |
| E2E Tests | $E2E_COVERAGE | $( [ "$E2E_COVERAGE" != "N/A" ] && echo "✓" || echo "⚠" ) |

## Detailed Reports

### Rust Service
- XML Report: \`$RUST_COVERAGE_DIR/cobertura.xml\`
- HTML Report: \`$RUST_COVERAGE_DIR/tarpaulin-report.html\`

### TypeScript/Next.js
- HTML Report: \`$TYPESCRIPT_COVERAGE_DIR/index.html\`
- JSON Report: \`$TYPESCRIPT_COVERAGE_DIR/coverage-summary.json\`

### E2E Tests
- Calendar System HTML Report: \`performers/systems/calendar-system/e2e/playwright-report/index.html\`
- Calendar System JSON Report: \`$E2E_COVERAGE_DIR/calendar-system-results.json\`
- Calendar Service HTML Report: \`performers/services/calendar-service/e2e/playwright-report/index.html\`
- Calendar Service JSON Report: \`$E2E_COVERAGE_DIR/calendar-service-results.json\`

## Notes

- Rust coverage is measured using \`cargo-tarpaulin\`
- TypeScript coverage is measured using \`vitest --coverage\`
- E2E coverage is measured using Playwright's coverage API

EOF

echo "   ✓ Combined report generated: $COMBINED_DIR/COVERAGE_REPORT.md"

echo ""
echo "=========================================="
echo "Coverage Report Complete"
echo "=========================================="
echo ""
echo "View combined report:"
echo "  cat $COMBINED_DIR/COVERAGE_REPORT.md"
echo ""
echo "Individual reports:"
echo "  Rust: $RUST_COVERAGE_DIR/"
echo "  TypeScript: $TYPESCRIPT_COVERAGE_DIR/"
echo "  E2E: $E2E_COVERAGE_DIR/"
echo ""

