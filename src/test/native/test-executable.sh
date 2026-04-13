#!/bin/sh

set -o errexit
set -o nounset
set -o xtrace

cd "$(dirname "$0")/../../.." || exit 1

expectedOutput="${1:-artifact} version \"${3:-revision}\""
executable="./target/${1:-artifact}-${2:-platform}-${3:-revision}"
actualOutput=$(${executable} --version)
if [ "$actualOutput" != "$expectedOutput" ]; then
    echo "The output is wrong. Expected:"
    echo "$expectedOutput"
    echo "Got:"
    echo "$actualOutput"
    exit 1
fi

# add custom tests here:

# make sure that it works
${executable}
# and changes nothing
git diff --exit-code
