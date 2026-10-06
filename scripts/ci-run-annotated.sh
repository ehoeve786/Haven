#!/usr/bin/env bash
# Run a command; if it fails, surface its compiler/test errors as a GitHub
# annotation, which is readable through the check-runs API even when the raw
# job log isn't (fork maintenance from a session without log access).
set -o pipefail
log="$(mktemp)"
"$@" 2>&1 | tee "$log"
status=$?
if [ $status -ne 0 ]; then
    errors="$(grep -E '^e: |^w: .*unresolved|FAILED$|tests completed|What went wrong|^> |Unresolved reference|Execution failed' "$log" | head -200)"
    errors="${errors//'%'/'%25'}"
    errors="${errors//$'\r'/}"
    errors="${errors//$'\n'/'%0A'}"
    echo "::error title=$1 failed::${errors:-no matching error lines; see the job log}"
fi
exit $status
