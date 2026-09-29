#!/usr/bin/env python3
"""Exercise the real CLI against isolated Git repositories without network access."""
from html.parser import HTMLParser
import json
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
    assert "Commits analyzed: 1" in run(repo)
    (repo / "file").write_text("changed\nmore\n")
    git(repo, "commit", "-qam", "change")
    output = run(repo)
    assert "Commits analyzed: 2" in output
    assert "Added: 3\nDeleted: 1\nNet: +2" in output
    report = root / "reports" / "report.json"
    run(repo, "--json", str(report))
    data = json.loads(report.read_text())
    assert data["schemaVersion"] == 1
    assert data["summary"] == dict(commits=2, contributors=1, added=3, deleted=1, net=2)
    assert data["authors"][0] == dict(name="Test", email="test@example.org", commits=2, added=3, deleted=1, net=2)
    report.write_text("old contents")
    run(repo, "--json", str(report))
    assert json.loads(report.read_text()) == data
    html_report = root / "report.html"
    run(repo, "--json", str(report), "--html", str(html_report))
    html = html_report.read_text()
    assert "<!DOCTYPE html>" in html and "test@example.org" in html
    assert "<script" not in html and "http://" not in html and "https://" not in html
    HTMLParser().feed(html)
    subdir = repo / "subdir"
    subdir.mkdir()
    assert "Repository: repo" in run(subdir)
    git(repo, "checkout", "--detach", "-q")
    assert "(detached HEAD)" in run(repo)
    run(root, success=False)
    # Raw Git objects preserve angle brackets that git commit sanitizes in names.
    payload = 'Untrusted & "quoted" </script><script>alert(1)</script>'
    parent = git(repo, "rev-parse", "HEAD")
    tree = git(repo, "write-tree")
    identity = f'{payload} <evil@example.org> 1700000000 +0000'
    raw = f'tree {tree}\nparent {parent}\nauthor {identity}\ncommitter Test <test@example.org> 1700000000 +0000\n\nunsafe author\n'
    commit = subprocess.run(["git", "-C", str(repo), "hash-object", "-t", "commit", "-w", "--stdin"],
                            input=raw, text=True, env=ENV, check=True, capture_output=True).stdout.strip()
    git(repo, "update-ref", "HEAD", commit)
    run(repo, "--json", str(report), "--html", str(html_report))
    data = json.loads(report.read_text())
    canonical = git(repo, "log", "-1", "--format=%aN")
    assert any(a["name"] == canonical for a in data["authors"]), data
    assert '&quot;' not in canonical
    branch_payload = "bad</script><script>alert(1)</script>"
    git(repo, "checkout", "-qb", branch_payload)
    run(repo, "--json", str(report), "--html", str(html_report))
    assert json.loads(report.read_text())["repository"]["branch"] == branch_payload
    html = html_report.read_text()
    assert payload not in html and branch_payload not in html and "&lt;/script&gt;" in html
    assert "Untrusted &amp;" in html
    assert "<script" not in html
    HTMLParser().feed(html)
print("Repository metadata e2e passed.")
