#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
HEADER="SPDX-License-Identifier: AGPL-3.0-only"
fail=0
while IFS= read -r file; do
  case "$file" in
    LICENSE|NOTICE|CLA.md|CODEOWNERS) continue ;;
    *.kt|*.kts|*.xml|*.md|*.sh|*.yml|*.yaml) ;;
    *) continue ;;
  esac
  [ -f "$file" ] || continue
  head -n 4 "$file" | grep -q "$HEADER" || { echo "Missing SPDX: $file"; fail=1; }
done < <(git ls-files --cached --others --exclude-standard)
((fail == 0)) || exit 1
echo "SPDX guard: OK"
