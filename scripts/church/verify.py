#!/usr/bin/env python3
"""The upstream T0 server/web/agent commands, run in a candidate worktree."""
from pathlib import Path
import shutil
import subprocess
import sys

repo = Path(sys.argv[1]).resolve()
shutil.copyfile(repo / "server/build.properties.example", repo / "server/build.properties")
commands = (
    (repo, ["mvn", "-B", "-pl", "server", "-am", "test"]),
    (repo, ["mvn", "-B", "-DskipTests", "package"]),
    (repo / "web", ["npm", "ci"]),
    (repo / "web", ["npm", "run", "build"]),
    (repo / "agent-android", ["./gradlew", "--no-daemon", "detekt", "assembleDebug",
                              "lintDebug", "testDebugUnitTest"]),
)
for cwd, command in commands:
    print(f"Verify {repo.name}: {' '.join(command)}", flush=True)
    subprocess.run(command, cwd=cwd, check=True)
if repo.name == "church":
    subprocess.run(["python3", "-m", "unittest", "discover", "-s", "scripts/church",
                    "-p", "test_*.py"], cwd=repo, check=True)
    # The console changes also affect upstream's edge tier.
    subprocess.run(["scripts/edge-check.sh"], cwd=repo, check=True)
    subprocess.run(["docker", "build", "-f", "docker/web.Dockerfile", "-t",
                    "mdmesh-church-sync-web:check", "."], cwd=repo, check=True)
