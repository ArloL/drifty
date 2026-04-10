#!/bin/bash
set -euo pipefail

# Only needed in Claude Code Web remote environment
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

SCRIPTS_DIR="${CLAUDE_PROJECT_DIR}/.claude/scripts"

{
    echo "export JAVA_HOME=\"\${HOME}/.local/share/java/temurin\""
    # Single-threaded artifact download avoids parallel 407s through the proxy
    echo "export MAVEN_ARGS=\"\${MAVEN_ARGS:+\$MAVEN_ARGS }--batch-mode  --no-transfer-progress --define maven.artifact.threads=1 --threads 1\""
    echo "export PATH=\${JAVA_HOME}/bin:\${PATH}"
    echo "export JAVA_TOOL_OPTIONS=\"\${JAVA_TOOL_OPTIONS:+\$JAVA_TOOL_OPTIONS }-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts\""
    echo "export ZIG_HOME=\"\${HOME}/.local/share/zig\""
    echo "export PATH=\${ZIG_HOME}:\${PATH}"
} >> "${CLAUDE_ENV_FILE}"

# shellcheck disable=SC1090
source "${CLAUDE_ENV_FILE}"

echo "Configuring Maven proxy..."
python3 "${SCRIPTS_DIR}/configure-maven-proxy.py"

echo "Installing Temurin JDK..."
python3 "${SCRIPTS_DIR}/install-temurin.py"

echo "Installing Zig..."
python3 "${SCRIPTS_DIR}/install-zig.py"

"${SCRIPTS_DIR}/warmup-maven.sh"
