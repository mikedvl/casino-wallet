#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

fail() { printf 'Verification prerequisite missing: %s\n' "$*" >&2; exit 1; }
for executable in java node npm docker; do
  command -v "$executable" >/dev/null 2>&1 || fail "$executable"
done

JAVA_VERSION="$(java -version 2>&1)"
[[ "$JAVA_VERSION" == *'version "21.'* ]] || fail 'Java 21 (check JAVA_HOME and PATH)'
node -e 'const [major, minor] = process.versions.node.split(".").map(Number); process.exit(major === 20 && minor >= 9 ? 0 : 1)' \
  || fail 'Node 20.9 or newer within Node 20; see frontend/.nvmrc'
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2'
docker info >/dev/null 2>&1 || fail 'a running Docker daemon (required by Testcontainers)'

if [[ -z "${CHROME_BIN:-}" ]]; then
  if [[ -x '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' ]]; then
    export CHROME_BIN='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
  else
    for browser in google-chrome google-chrome-stable chromium chromium-browser; do
      if command -v "$browser" >/dev/null 2>&1; then
        export CHROME_BIN="$(command -v "$browser")"
        break
      fi
    done
  fi
fi
[[ -n "${CHROME_BIN:-}" && -x "$CHROME_BIN" ]] || fail 'Chrome/Chromium; set CHROME_BIN to its executable'

printf '\n[1/4] Backend checks, tests and executable JAR\n'
(cd backend && ./gradlew --no-daemon check bootJar)

printf '\n[2/4] Frontend clean dependency installation and headless tests\n'
(cd frontend && npm ci && npm test)

printf '\n[3/4] Angular production build\n'
(cd frontend && npm run build)

printf '\n[4/4] Docker Compose configuration\n'
docker compose config --quiet
printf '\nVerification passed. Run the Compose smoke test separately.\n'
