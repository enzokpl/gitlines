#!/usr/bin/env python3
"""Run the full CLI against isolated real Git histories; no services or network."""
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

CLI = sys.argv[1:]
ENV = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
           GIT_AUTHOR_DATE="2020-01-01T12:00:00+00:00", GIT_COMMITTER_DATE="2020-01-01T12:00:00+00:00")
for key in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_AUTHOR_NAME",
            "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL", "GIT_CONFIG_COUNT"):
    ENV.pop(key, None)


class HtmlAudit(HTMLParser):
    """Check real HTML elements, rather than searching repository-controlled text."""
    def __init__(self):
        super().__init__()
        self.tags = []
        self.text = []

    def handle_starttag(self, tag, attrs):
        self.tags.append(tag)
        assert tag not in ("script", "iframe", "object", "embed", "link", "img"), tag
        for name, value in attrs:
            assert not name.startswith("on"), (name, value)
            assert name not in ("src", "href"), (name, value)

    def handle_data(self, data):
        self.text.append(data)


def git(repo, *args, input=None):
    result = subprocess.run(["git", "-C", str(repo), *args], env=ENV, check=True,
                            input=input, text=True, capture_output=True)
    return result.stdout.rstrip("\n")


def invoke(repo=None, *args, code=0, env=ENV, cwd=None):
    arguments = [] if repo is None else [str(repo)]
    result = subprocess.run([*CLI, *arguments, *args], env=env, cwd=cwd, capture_output=True, text=True)
    assert result.returncode == code, (result.args, result.returncode, result.stdout, result.stderr)
    assert "Exception" not in result.stderr and "\tat " not in result.stderr, result.stderr
    if code == 1:
        assert "error:" in result.stderr, result.stderr
    return result


def init(root, name):
    repo = root / name
    repo.mkdir()
    git(repo, "init", "-q", "-b", "main")
    git(repo, "config", "user.name", "Alice")
    git(repo, "config", "user.email", "alice@example.org")
    git(repo, "config", "commit.gpgsign", "false")
    return repo


def commit(repo, name="Alice", email="alice@example.org", message="fixture"):
    git(repo, "add", "--all")
    git(repo, "-c", f"user.name={name}", "-c", f"user.email={email}",
        "commit", "-qm", message, "--allow-empty")


def report(repo, destination):
    json_file = destination / "report.json"
    html_file = destination / "report.html"
    result = invoke(repo, "--json", str(json_file), "--html", str(html_file))
    data = json.loads(json_file.read_text(encoding="utf-8"))
    assert data["schemaVersion"] == 1
    html = html_file.read_text(encoding="utf-8")
    audit = HtmlAudit()
    audit.feed(html)
    assert "table" in audit.tags or "No contributions found." in "".join(audit.text)
    tree = ET.fromstring(html)
    assert tree.tag == "html"
    assert len(tree.findall(".//style")) == 1
    assert tree.find(".//meta[@http-equiv='Content-Security-Policy']") is not None
    assert {file.name for file in destination.iterdir()} == {"report.json", "report.html"}
    for author in data["authors"]:
        assert author["name"] in "".join(audit.text)
        assert author["email"] in "".join(audit.text)
    rows = tree.findall(".//tbody/tr")
    assert len(rows) == len(data["authors"])
    for row, author in zip(rows, data["authors"]):
        expected = [author["name"], author["email"], f'{author["commits"]:,}',
                    f'{author["added"]:,}', f'{author["deleted"]:,}', f'{author["net"]:+,}']
        assert [(cell.text or "") for cell in row] == expected
    return data, result.stdout, html


def author(name, email, commits, added, deleted):
    return dict(name=name, email=email, commits=commits, added=added, deleted=deleted, net=added-deleted)


def raw_commit(repo, name, email):
    parent = git(repo, "rev-parse", "HEAD")
    tree = git(repo, "write-tree")
    identity = f"{name} <{email}> 1700000000 +0000"
    raw = (f"tree {tree}\nparent {parent}\nauthor {identity}\n"
           "committer Alice <alice@example.org> 1700000000 +0000\n\nraw identity\n")
    revision = git(repo, "hash-object", "-t", "commit", "-w", "--stdin", input=raw)
    git(repo, "update-ref", "HEAD", revision)


with tempfile.TemporaryDirectory(prefix="gitlines-e2e-") as directory:
    root = Path(directory)
    repo = init(root, "simple")
    (repo / "one").write_text("one\ntwo\nthree\n", encoding="utf-8")
    (repo / "two").write_text("a\nb\n", encoding="utf-8")
    commit(repo)
    (repo / "one").write_text("one\nTWO\nthree\nfour\n", encoding="utf-8")
    (repo / "two").write_text("a\n", encoding="utf-8")
    commit(repo, "Bob", "bob@example.org")
    data, output, html = report(repo, root / "simple-reports")
    assert data["summary"] == dict(commits=2, contributors=2, added=7, deleted=2, net=5)
    assert data["authors"] == [author("Alice", "alice@example.org", 1, 5, 0),
                               author("Bob", "bob@example.org", 1, 2, 2)]
    assert output.index("Alice <") < output.index("Bob <")
    assert "Commits analyzed: 2" in output and "Net: +5" in output
    assert data["repository"]["revision"] == git(repo, "rev-parse", "HEAD")
    assert data["repository"]["branch"] == "main" and not data["repository"]["shallow"]
    assert Path(data["repository"]["path"]) == repo.resolve()
    # Defaults, subdirectories, dirty working tree and deterministic reports.
    baseline = (root / "simple-reports" / "report.json").read_bytes()
    (repo / "subdir").mkdir()
    assert "Repository: simple" in invoke(repo / "subdir").stdout
    assert invoke(None, cwd=repo / "subdir").stdout == output
    (repo / "one").write_text("not committed\n", encoding="utf-8")
    (repo / "untracked").write_text("not committed\n", encoding="utf-8")
    before = git(repo, "status", "--porcelain=v1")
    dirty, _, _ = report(repo, root / "simple-reports")
    assert dirty == data
    assert (root / "simple-reports" / "report.json").read_bytes() == baseline
    assert git(repo, "status", "--porcelain=v1") == before
    git(repo, "checkout", "--detach", "-q")
    detached, _, _ = report(repo, root / "detached-reports")
    assert detached["repository"]["branch"] is None and detached["summary"] == data["summary"]
    # Report overwrite, invalid destinations and error/usage exit codes.
    target = root / "overwrite.json"
    target.write_text("previous contents")
    invoke(repo, "--json", str(target))
    assert json.loads(target.read_text())["summary"] == data["summary"]
    link = root / "report-link.json"
    link.symlink_to(target)
    previous = target.read_bytes()
    invoke(repo, "--json", str(link), code=1)
    assert target.read_bytes() == previous and link.is_symlink()
    invoke(repo, "--json", str(root), code=1)
    invoke(repo, "--json", str(root / "blocked" / "report.json"), code=0)
    (root / "parent-file").write_text("file")
    invoke(repo, "--json", str(root / "parent-file" / "report.json"), code=1)
    invoke(repo, "--json", str(target), "--html", str(target), code=2)
    invoke(repo, "--json", str(target), "--html", str(link), code=2)
    invoke(repo, "--unknown", code=2)
    invoke(repo, "--json", code=2)
    invoke(root, code=1)
    invoke(root / "missing", code=1)
    invoke(repo / "one", code=1)
    # Empty repositories support all renderers.
    empty = init(root, "empty")
    empty_data, empty_output, _ = report(empty, root / "empty-reports")
    assert empty_data["authors"] == []
    assert empty_data["summary"] == dict(commits=0, contributors=0, added=0, deleted=0, net=0)
    assert empty_data["repository"]["revision"] is None
    assert "No contributions found." in empty_output
    # Tabs, spaces, newlines, Unicode, binaries, rename pairs and empty commits.
    weird = init(root, "weird")
    paths = ["with space", "with\ttab", "João", "with\nnewline"]
    for filename in paths:
        (weird / filename).write_text("line\n", encoding="utf-8")
    (weird / "binary").write_bytes(b"\x00\x01\x02binary")
    commit(weird, "João da Silva", "joao@example.org")
    git(weird, "mv", "with\ttab", "renamed\nfile")
    (weird / "binary").write_bytes(b"\x00\x03changed binary")
    commit(weird, "João da Silva", "joao@example.org")
    commit(weird, "João da Silva", "joao@example.org", "empty commit")
    weird_data, weird_output, _ = report(weird, root / "weird-reports")
    assert weird_data["authors"] == [author("João da Silva", "joao@example.org", 3, 4, 0)]
    assert "João da Silva" in weird_output
    # Exact deterministic ties and distinct identities despite shared names.
    ties = init(root, "ties")
    for name, email, filename in [("Same", "z@example.org", "z"), ("Same", "a@example.org", "a"),
                                  ("Before", "b@example.org", "b")]:
        (ties / filename).write_text("line\n")
        commit(ties, name, email)
    ties_data, _, _ = report(ties, root / "ties-reports")
    assert [(a["name"], a["email"]) for a in ties_data["authors"]] == [
        ("Before", "b@example.org"), ("Same", "a@example.org"), ("Same", "z@example.org")]
    # Git's native mailmap canonicalizes two author identities.
    mapped = init(root, "mapped")
    (mapped / "file").write_text("first\n")
    commit(mapped, "Old Name", "old@example.org")
    (mapped / "file").write_text("first\nsecond\n")
    commit(mapped, "New Name", "new@example.org")
    (mapped / ".mailmap").write_text("New Name <new@example.org> Old Name <old@example.org>\n")
    commit(mapped, "Mapper", "mapper@example.org")
    mapped_data, _, _ = report(mapped, root / "mapped-reports")
    assert mapped_data["authors"] == [author("New Name", "new@example.org", 2, 2, 0),
                                     author("Mapper", "mapper@example.org", 1, 1, 0)]
    # Both sides of a merge are counted; the merge commit is excluded.
    merged = init(root, "merged")
    (merged / "base").write_text("base\n")
    commit(merged)
    git(merged, "checkout", "-qb", "feature")
    (merged / "feature").write_text("feature\n")
    commit(merged)
    git(merged, "checkout", "-q", "main")
    (merged / "main").write_text("main\n")
    commit(merged, "Bob", "bob@example.org")
    git(merged, "merge", "-q", "--no-ff", "-m", "merge", "feature")
    merged_data, _, _ = report(merged, root / "merged-reports")
    assert merged_data["summary"] == dict(commits=3, contributors=2, added=3, deleted=0, net=3)
    assert merged_data["authors"] == [author("Alice", "alice@example.org", 2, 2, 0),
                                     author("Bob", "bob@example.org", 1, 1, 0)]
    # Local file transport creates a shallow clone without any network service.
    shallow = root / "shallow"
    git(root, "clone", "-q", "--depth=1", weird.as_uri(), str(shallow))
    shallow_data, _, shallow_html = report(shallow, root / "shallow-reports")
    assert shallow_data["repository"]["shallow"] is True
    assert shallow_data["summary"]["commits"] == 1
    assert "Shallow clone" in shallow_html
    # Author injection attempts must remain canonical Git data and escaped text.
    malicious = init(root, 'repo & "quoted"')
    commit(malicious)
    raw_commit(malicious, 'Untrusted & "quoted" </script><script>alert(1)</script>', "evil@example.org")
    canonical_name = git(malicious, "log", "-1", "--format=%aN")
    canonical_email = git(malicious, "log", "-1", "--format=%aE")
    raw_commit(malicious, "No Email", "")
    branch_payload = "bad</script><script>alert(1)</script>"
    git(malicious, "checkout", "-qb", branch_payload)
    malicious_data, _, malicious_html = report(malicious, root / "malicious-reports")
    assert malicious_data["repository"]["branch"] == branch_payload
    assert author(canonical_name, canonical_email, 1, 0, 0) in malicious_data["authors"]
    assert author("No Email", "", 1, 0, 0) in malicious_data["authors"]
    assert branch_payload not in malicious_html and "&lt;/script&gt;" in malicious_html
    assert "Untrusted &amp;" in malicious_html and "<script" not in malicious_html
    # A native binary needs only Git on PATH, and Git failures publish no report.
    real_git = shutil.which("git")
    tools = root / "tools"
    tools.mkdir()
    fake_git = tools / "git"
    fake_git.write_text('#!/bin/sh\nfor arg do\n  if [ "$arg" = log ]; then echo "fixture Git failure" >&2; exit 37; fi\ndone\n'
                        f'exec "{real_git}" "$@"\n')
    fake_git.chmod(0o755)
    fake_env = dict(ENV, PATH=str(tools))
    if len(CLI) > 1:
        (tools / "java").symlink_to(shutil.which("java"))
    failed_report = root / "failed.json"
    failed_report.write_text("preserve me")
    failed = invoke(weird, "--json", str(failed_report), env=fake_env, code=1)
    assert "status 37" in failed.stderr
    assert failed_report.read_text() == "preserve me"
    fake_git.unlink()
    fake_git.symlink_to(real_git)
    if len(CLI) == 1:
        assert "Commits analyzed: 3" in invoke(weird, env=fake_env).stdout
    fake_git.unlink()
    invoke(weird, env=fake_env, code=1)
    assert not list(root.rglob(".gitlines-*.tmp"))

print("All e2e scenarios passed: exact counts, ordering, mailmap, Unicode, unsafe data, unusual paths, "
      "binaries, renames, empty/detached/shallow repositories, merges, dirty trees, JSON/HTML and errors.")
