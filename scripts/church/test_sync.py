import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("church_sync", Path(__file__).with_name("sync.py"))
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()


class SyncTest(unittest.TestCase):
    def setUp(self):
        # Retain fixtures for inspection; use trash to remove local test fixtures.
        self.root = Path(tempfile.mkdtemp(prefix="mdmesh-sync-test-"))
        self.origin = self.root / "origin.git"
        self.repo = self.root / "repo"
        subprocess.run(["git", "init", "--bare", str(self.origin)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["git", "clone", str(self.origin), str(self.repo)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        git(self.repo, "config", "user.name", "Sync test")
        git(self.repo, "config", "user.email", "sync@example.invalid")
        git(self.repo, "checkout", "-b", "main")
        self.commit("base.txt", "base\n")
        base = git(self.repo, "rev-parse", "HEAD")
        for index, branch in enumerate(sync.TOPICS):
            git(self.repo, "checkout", "-b", branch, base)
            self.commit(f"topic-{index}.txt", f"topic {index}\n")
        git(self.repo, "checkout", "-b", sync.INTEGRATION, base)
        for branch in sync.TOPICS:
            git(self.repo, "merge", "--no-ff", "--no-edit", branch)
        self.commit("CHURCH.md", "church metadata\n")
        git(self.repo, "push", "origin", *sync.TOPICS, sync.INTEGRATION)
        git(self.repo, "checkout", "main")
        self.commit("new-upstream.txt", "new upstream\n")
        git(self.repo, "update-ref", "refs/remotes/upstream/main", "HEAD")
        git(self.repo, "fetch", "origin")
        self.initial = self.remote_refs()

    def commit(self, name, content):
        (self.repo / name).write_text(content)
        git(self.repo, "add", name)
        git(self.repo, "commit", "-m", f"Write {name}")

    def remote_refs(self):
        return {b: git(self.origin, "rev-parse", f"refs/heads/{b}")
                for b in (*sync.TOPICS, sync.INTEGRATION)}

    def stage(self, verify=lambda path: None):
        return sync.stage(self.repo, self.root / "candidates", verify)

    def test_success_rebases_topics_and_preserves_church_metadata(self):
        verified = []
        old, candidates = self.stage(lambda path: verified.append(path.name))
        self.assertEqual(len(verified), 4)
        self.assertEqual(git(self.repo, "config", "user.name"), "Sync test")
        self.assertEqual(git(self.repo, "config", "user.email"), "sync@example.invalid")
        self.assertEqual(self.remote_refs(), self.initial)
        upstream = git(self.repo, "rev-parse", "upstream/main")
        for sha in candidates.values():
            git(self.repo, "merge-base", "--is-ancestor", upstream, sha)
        self.assertEqual(git(self.repo, "show", f"{candidates[sync.INTEGRATION]}:CHURCH.md"),
                         "church metadata")
        sync.publish(self.repo, old, candidates)
        self.assertEqual(self.remote_refs(), candidates)
        git(self.repo, "fetch", "origin")
        second = self.root / "second"
        old2, candidates2 = sync.stage(self.repo, second, lambda path: None)
        self.assertEqual(git(self.repo, "show", f"{candidates2[sync.INTEGRATION]}:CHURCH.md"),
                         "church metadata")
        for index in range(3):
            self.assertIn(f"topic {index}", git(self.repo, "show",
                          f"{candidates2[sync.INTEGRATION]}:topic-{index}.txt"))

    def test_failed_verification_leaves_all_remote_branches_unchanged(self):
        def fail(path):
            raise RuntimeError("Test failure")
        with self.assertRaises(RuntimeError):
            self.stage(fail)
        self.assertEqual(self.remote_refs(), self.initial)

    def test_rebase_conflict_leaves_all_remote_branches_unchanged(self):
        self.commit("topic-0.txt", "conflicting upstream\n")
        git(self.repo, "update-ref", "refs/remotes/upstream/main", "HEAD")
        with self.assertRaises(subprocess.CalledProcessError):
            self.stage()
        self.assertEqual(self.remote_refs(), self.initial)

    def test_topic_merge_conflict_leaves_all_remote_branches_unchanged(self):
        for index, branch in enumerate(sync.TOPICS[:2]):
            git(self.repo, "checkout", branch)
            self.commit("overlap.txt", f"topic change {index}\n")
            git(self.repo, "push", "origin", branch)
        git(self.repo, "fetch", "origin")
        self.initial = self.remote_refs()
        with self.assertRaises(subprocess.CalledProcessError):
            self.stage()
        self.assertEqual(self.remote_refs(), self.initial)

    def test_stale_lease_aborts_the_entire_atomic_publication(self):
        old, candidates = self.stage()
        branch = sync.TOPICS[1]
        git(self.origin, "update-ref", f"refs/heads/{branch}", self.initial[sync.TOPICS[0]])
        moved = self.remote_refs()
        with self.assertRaises(subprocess.CalledProcessError):
            sync.publish(self.repo, old, candidates)
        self.assertEqual(self.remote_refs(), moved)


if __name__ == "__main__":
    unittest.main()
