#!/usr/bin/env bash
set -euo pipefail
op_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$op_root"
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  printf '请先设置 ANDROID_HOME 或 ANDROID_SDK_ROOT，参见 docs/BUILD.md。\n' >&2
  exit 2
fi
if [[ "$#" -eq 0 ]]; then
  set -- :common:testDebugUnitTest :shared:testDebugUnitTest :mobile:assembleDebug
fi
exec bash ./gradlew -Pp7Profile=true --console=plain "$@"
