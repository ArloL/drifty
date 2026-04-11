#!/bin/bash
set -o errexit
set -o nounset
set -o pipefail

# Only needed in Claude Code Web remote environment
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

mise install

{
    mise env --shell bash
    # Single-threaded artifact download avoids parallel 407s through the proxy
    echo "export MAVEN_ARGS=\"\${MAVEN_ARGS:+\$MAVEN_ARGS }--batch-mode  --no-transfer-progress --define maven.artifact.threads=1 --threads 1\""
    echo "export JAVA_TOOL_OPTIONS=\"\${JAVA_TOOL_OPTIONS:+\$JAVA_TOOL_OPTIONS }-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts\""
} >> "${CLAUDE_ENV_FILE}"

# shellcheck disable=SC1090
source "${CLAUDE_ENV_FILE}"

SCRIPTS_DIR="${CLAUDE_PROJECT_DIR}/.claude/scripts"

echo "Configuring Maven proxy..."
python3 "${SCRIPTS_DIR}/configure-maven-proxy.py"

"${SCRIPTS_DIR}/warmup-maven.sh"
