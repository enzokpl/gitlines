#!/usr/bin/env python3
"""Exercise the real CLI against isolated Git repositories without network access."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

CLI = sys.argv[1:]
ENV = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], env=ENV, check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()


def run(repo, *args, success=True):
    result = subprocess.run([*CLI, str(repo), *args], env=ENV, capture_output=True, text=True)
    assert result.returncode == (0 if success else 1), (result.returncode, result.stderr)
    assert "Exception" not in result.stderr, result.stderr
    return result.stdout


with tempfile.TemporaryDirectory(prefix="gitlines-e2e-") as directory:
    root = Path(directory)
    repo = root / "repo"
    repo.mkdir()
    git(repo, "init", "-q", "-b", "main")
    git(repo, "config", "user.name", "Test")
    git(repo, "config", "user.email", "test@example.org")
    assert "(no commits)" in run(repo)
    (repo / "file").write_text("hello\n")
    git(repo, "add", "file")
    git(repo, "commit", "-qm", "first")
    subdir = repo / "subdir"
    subdir.mkdir()
    assert "Repository: repo" in run(subdir)
    git(repo, "checkout", "--detach", "-q")
    assert "(detached HEAD)" in run(repo)
    run(root, success=False)
print("Repository metadata e2e passed.")
