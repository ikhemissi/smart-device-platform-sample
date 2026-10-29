#!/usr/bin/env bash
# Outdated dependency report (spec continuous-integration): direct dependencies with a newer version, as Markdown
# tables — Maven, npm, Python (uv), Terraform providers and the CI workflow's actions. Informational only: it always
# exits 0, and a lookup that fails is reported as a note. In CI the output goes to the run summary; locally, to stdout.
# Needs python3, and for their sections ./mvnw (JDK 25), uv and the GitHub CLI (gh, authenticated or GH_TOKEN).
#   scripts/report-outdated.sh
set -uo pipefail
cd "$(dirname "$0")/.."

MAVEN_LOG="$(mktemp)"
UV_LOG="$(mktemp)"
trap 'rm -f "$MAVEN_LOG" "$UV_LOG"' EXIT
# Dependencies and imported BOMs declared by the build (not every artifact the BOMs manage), and parent POMs.
# Stable releases only (no alpha, beta, milestone, release candidate or snapshot).
PRERELEASE='(?i).+[-.](alpha|beta|m|rc|cr|milestone|preview|snapshot)[-.]?[0-9]*.*'
./mvnw -B -ntp org.codehaus.mojo:versions-maven-plugin:2.23.0:display-dependency-updates \
  -DprocessDependencyManagementTransitive=false -Dversions.outputLineWidth=200 -Dmaven.version.ignore="$PRERELEASE" \
  >"$MAVEN_LOG" 2>&1 || echo "MAVEN-FAILED" >>"$MAVEN_LOG"
./mvnw -B -ntp org.codehaus.mojo:versions-maven-plugin:2.23.0:display-parent-updates -Dmaven.version.ignore="$PRERELEASE" \
  >>"$MAVEN_LOG" 2>&1 || true
(cd agents/device-analyst && uv tree --outdated --depth 1 --frozen) >"$UV_LOG" 2>&1 || echo "UV-FAILED" >>"$UV_LOG"

python3 - "$MAVEN_LOG" "$UV_LOG" <<'PY' || echo "_Outdated report failed unexpectedly._"
import json, re, subprocess, sys, urllib.request
from pathlib import Path

maven_log, uv_log = Path(sys.argv[1]).read_text(), Path(sys.argv[2]).read_text()


def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=20) as r:
        return json.load(r)


def table(title, rows, note=None):
    print(f"\n### {title}\n")
    if note:
        print(f"_{note}_\n")
    elif not rows:
        print("Everything is up to date.\n")
    else:
        print("| Dependency | Current | Latest |\n|---|---|---|")
        for name, current, latest in sorted(set(rows)):
            print(f"| `{name}` | {current} | {latest} |")


print("## Outdated dependencies\n\nInformational: newer versions of direct dependencies. This report never fails the run.")

# Maven
rows = []
for m in re.finditer(r"^\[INFO\]\s+(\S+:\S+)\s+\.+\s+(\S+)\s+->\s+(\S+)", maven_log, re.M):
    rows.append(m.groups())
for m in re.finditer(r"^\[INFO\] Parent \S+ (\S+:\S+)\s.*?(\S+)\s+->\s+(\S+)", maven_log, re.M):
    rows.append(m.groups())
table("Java (Maven)", rows, "Maven lookup failed." if "MAVEN-FAILED" in maven_log else None)

# npm: versions installed by the lock file versus the registry's latest
rows, note = [], None
try:
    portal = Path("web/portal")
    manifest = json.loads((portal / "package.json").read_text())
    lock = json.loads((portal / "package-lock.json").read_text())["packages"]
    for name in {**manifest.get("dependencies", {}), **manifest.get("devDependencies", {})}:
        current = lock.get(f"node_modules/{name}", {}).get("version")
        latest = get_json(f"https://registry.npmjs.org/{name.replace('/', '%2F')}/latest")["version"]
        if current and current != latest:
            rows.append((name, current, latest))
except Exception as e:  # noqa: BLE001 - informational report
    note = f"npm lookup failed: {e}"
table("Web portal (npm)", rows, note)

# Python (uv)
rows = [(m.group(1), m.group(2), m.group(3)) for m in re.finditer(r"── (\S+) v(\S+).*?\(latest: v(\S+)\)", uv_log)]
table("AI agent (Python)", rows, "uv lookup failed." if "UV-FAILED" in uv_log else None)

# Terraform providers: lock file versus registry
rows, note = [], None
try:
    lock = Path("infra/.terraform.lock.hcl").read_text()
    for source, version in re.findall(r'provider "registry\.terraform\.io/([^"]+)" \{\s*version\s*=\s*"([^"]+)"', lock):
        latest = get_json(f"https://registry.terraform.io/v1/providers/{source}")["version"]
        if latest != version:
            rows.append((source, version, latest))
except Exception as e:  # noqa: BLE001
    note = f"Terraform registry lookup failed: {e}"
table("Infrastructure (Terraform providers)", rows, note)

# GitHub Actions pinned in the workflows (uses: owner/repo[/path]@sha # vX.Y.Z)
rows, note = [], None
try:
    pins = set()
    for workflow in Path(".github/workflows").glob("*.y*ml"):
        pins |= set(re.findall(r"uses:\s*([\w.-]+/[\w.-]+)[\w./-]*@[0-9a-f]{40}\s*#\s*(v[\w.]+)", workflow.read_text()))
    for repo, version in pins:
        # Highest vX.Y.Z tag: some repositories publish other releases (e.g. CodeQL bundles) as "latest".
        tags = subprocess.run(["gh", "api", f"repos/{repo}/tags?per_page=100", "--jq", ".[].name"],
                              capture_output=True, text=True, timeout=30, check=True).stdout.split()
        semver = [t for t in tags if re.fullmatch(r"v\d+(\.\d+)*", t)]
        latest = max(semver, key=lambda t: tuple(int(x) for x in t[1:].split("."))) if semver else None
        if latest and latest != version:
            rows.append((repo, version, latest))
except Exception as e:  # noqa: BLE001
    note = f"GitHub lookup failed: {e}"
table("CI (GitHub Actions)", rows, note)
PY
exit 0
