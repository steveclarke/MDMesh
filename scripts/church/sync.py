#!/usr/bin/env python3
"""Rebase topic branches, rebuild the church merge, test, then publish atomically."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

TOPICS = (
    "feat/call-and-mobile-network-policy",
    "fix/kiosk-locked-recovery",
    "fix/apk-download-client",
)
INTEGRATION = "church/main"
FORK_URLS = {
    "https://github.com/steveclarke/MDMesh.git",
    "https://github.com/steveclarke/MDMesh",
    "git@github.com:steveclarke/MDMesh.git",
}


def git(repo, *args, capture=False):
    result = subprocess.run(["git", "-c", "user.name=MDMesh church sync",
                             "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
                             "-C", str(repo), *args], check=True,
                            text=True, stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def stage(repo, workspace, verify):
    """Make disposable candidates; leave local and remote source branches untouched."""
    old = {branch: git(repo, "rev-parse", f"origin/{branch}", capture=True)
           for branch in (*TOPICS, INTEGRATION)}
    upstream = git(repo, "rev-parse", "upstream/main", capture=True)
    candidates = {}
    for index, branch in enumerate(TOPICS):
        path = workspace / f"topic-{index}"
        git(repo, "worktree", "add", "--detach", str(path), old[branch])
        git(path, "rebase", upstream)
        verify(path)
        candidates[branch] = git(path, "rev-parse", "HEAD", capture=True)

    path = workspace / "church"
    git(repo, "worktree", "add", "--detach", str(path), upstream)
    for branch in TOPICS:
        git(path, "merge", "--no-ff", "--no-edit", candidates[branch])
    # Church-only commits are on the first-parent spine. All topic changes must
    # enter church/main as --no-ff merges, never squashes or direct commits.
    base = git(repo, "merge-base", old[INTEGRATION], upstream, capture=True)
    extras = git(repo, "rev-list", "--reverse", "--first-parent", "--no-merges",
                 f"{base}..{old[INTEGRATION]}", capture=True).splitlines()
    if extras:
        git(path, "cherry-pick", *extras)
    verify(path)
    candidates[INTEGRATION] = git(path, "rev-parse", "HEAD", capture=True)
    return old, candidates


def publish(repo, old, candidates):
    # The leases detect human updates since fetch. --atomic guarantees that an
    # interrupted/conflicting publication cannot leave only some branches synced.
    leases = [f"--force-with-lease=refs/heads/{branch}:{sha}" for branch, sha in old.items()]
    refs = [f"{sha}:refs/heads/{branch}" for branch, sha in candidates.items()]
    git(repo, "push", "--atomic", *leases, "origin", *refs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="test candidates without publishing")
    args = parser.parse_args()
    repo = Path.cwd()
    if git(repo, "remote", "get-url", "origin", capture=True) not in FORK_URLS:
        raise SystemExit("Refusing to publish: origin must be steveclarke/MDMesh")
    if git(repo, "status", "--porcelain", capture=True):
        raise SystemExit("Save uncommitted work before syncing")
    git(repo, "fetch", "origin", "+refs/heads/*:refs/remotes/origin/*")
    upstream_url = "https://github.com/MDMesh-app/MDMesh.git"
    git(repo, "fetch", upstream_url, "+refs/heads/main:refs/remotes/upstream/main")
    workspace = Path(tempfile.mkdtemp(prefix="mdmesh-church-sync-"))
    print(f"Candidates and logs retained at {workspace}", flush=True)
    # Use the reviewed verifier from this checkout rather than a script fetched
    # from a rebased branch. Arguments are never evaluated by a shell.
    verifier = repo / "scripts/church/verify.py"

    def verify(path):
        subprocess.run(["python3", str(verifier), str(path)], check=True, env=os.environ.copy())

    old, candidates = stage(repo, workspace, verify)
    if not args.dry_run:
        publish(repo, old, candidates)
    for branch, sha in candidates.items():
        print(f"{branch}: {sha}")


if __name__ == "__main__":
    main()
