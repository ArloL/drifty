#!/bin/bash
set -euo pipefail

echo "Warming up Maven dependency cache..."

cd "${CLAUDE_PROJECT_DIR}"
for i in $(seq 1 10); do
    find "${HOME}/.m2/repository" -name "*.lastUpdated" -delete 2>/dev/null || true
    if ./mvnw --quiet test-compile 2>/dev/null; then
        echo "Maven warm-up succeeded on attempt ${i}"
        break
    fi
    echo "Maven warm-up attempt ${i} incomplete, retrying..."
done
