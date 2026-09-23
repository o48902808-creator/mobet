#!/usr/bin/env sh
set -eu
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
cat >&2 <<'MSG'
Mobet needs Gradle 8.9 and Java 17 to build locally.
No system Gradle was found. Install a JDK 17 and Gradle 8.9, then retry:
  ./gradlew :app:testDebugUnitTest
CI provisions both automatically via .github/workflows/android-ci.yml.
MSG
exit 127
