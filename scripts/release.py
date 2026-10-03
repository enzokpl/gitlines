#!/usr/bin/env python3
"""Build and test three local release packages; never publish them."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
TARGETS = ("macos-arm64", "linux-arm64", "linux-x86_64")


def run(*args, cwd=ROOT, capture=False):
    return subprocess.run(args, cwd=cwd, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def preflight(config, targets):
    if sys.version_info < (3, 12):
        raise RuntimeError("Python 3.12 or newer is required")
    for tool in ("git", "python3"):
        if not shutil.which(tool):
            raise RuntimeError(f"Required tool missing: {tool}")
    if "macos-arm64" in targets:
        if platform.system() != "Darwin" or platform.machine() != "arm64":
            raise RuntimeError("macos-arm64 must be built on an Apple Silicon Mac")
        for tool in ("java", "javac", "native-image", "mvn", "cc", "xcrun"):
            if not shutil.which(tool):
                raise RuntimeError(f"Required macOS build tool missing: {tool}")
        native = run("native-image", "--version", capture=True)
        java = subprocess.run(["java", "-version"], check=True, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout
        if config["macos_runtime_version"] not in java:
            raise RuntimeError("Java does not match the locked macOS GraalVM runtime")
        if (f"native-image {config['macos_native_image_version']} " not in native
                or config["macos_runtime_version"] not in native):
            raise RuntimeError("macOS GraalVM does not match packaging/toolchains.json")
        if f"Apache Maven {config['maven_version']} " not in run("mvn", "-version", capture=True):
            raise RuntimeError("Maven does not match packaging/toolchains.json")
        run("xcrun", "--show-sdk-path", capture=True)
    if any(t.startswith("linux-") for t in targets):
        if not shutil.which("docker"):
            raise RuntimeError("Docker is required for Linux builds")
        run("docker", "info", capture=True)
        run("docker", "buildx", "version", capture=True)


def snapshot(destination):
    # Include working copies of tracked and untracked sources, never ignored build outputs.
    paths = run("git", "ls-files", "--cached", "--others", "--exclude-standard", "-z", capture=True)
    for name in set(paths.split("\0")) - {""}:
        source = ROOT / name
        if not source.exists() and not source.is_symlink():
            continue
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target, follow_symlinks=False)


def package(binary_dir, destination, target, version, metadata, epoch):
    name = f"gitlines-{version}-{target}"
    staging = binary_dir / name
    staging.mkdir()
    shutil.copy2(binary_dir / "gitlines", staging / "gitlines")
    (staging / "gitlines").chmod(0o755)
    (staging / "INSTALL.txt").write_text(
        "Requires Git in PATH; Java and Docker are not required.\n"
        "Install: mkdir -p ~/.local/bin && cp gitlines ~/.local/bin/gitlines\n"
        "Add ~/.local/bin to PATH, then run: gitlines --version\n"
        "See the project README for supported platforms and macOS signing status.\n")
    (staging / "build-info.json").write_text(json.dumps(metadata, indent=2) + "\n")
    for file in ("toolchain.txt", "libraries.txt"):
        if (binary_dir / file).exists():
            shutil.copy2(binary_dir / file, staging / file)
    if (ROOT / "LICENSE").exists():
        shutil.copy2(ROOT / "LICENSE", staging / "LICENSE")
    archive = destination / f"{name}.tar.gz"
    # Stable tar metadata; native compilation and gzip headers need not be bit reproducible.
    with tarfile.open(archive, "w:gz") as tar:
        for item in sorted(staging.iterdir()):
            info = tar.gettarinfo(str(item), arcname=f"{name}/{item.name}")
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mtime = epoch
            with item.open("rb") as content:
                tar.addfile(info, content)
    with tempfile.TemporaryDirectory() as extracted:
        with tarfile.open(archive) as tar:
            tar.extractall(extracted, filter="data")
        if target == "macos-arm64":
            # Smoke-test the actual packaged executable without Java on PATH.
            tools = Path(extracted) / "tools"
            tools.mkdir()
            (tools / "git").symlink_to(shutil.which("git"))
            env = dict(os.environ, PATH=str(tools))
            subprocess.run([str(Path(extracted) / name / "gitlines"), "--version"],
                           env=env, check=True)
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check tools without building")
    parser.add_argument("--allow-dirty", action="store_true", help="Development packages only")
    parser.add_argument("--target", action="append", choices=TARGETS, help="Default: all three")
    args = parser.parse_args()
    targets = list(dict.fromkeys(args.target or TARGETS))
    config = json.loads((ROOT / "packaging/toolchains.json").read_text())
    preflight(config, targets)
    if args.check:
        print("Release prerequisites OK")
        return
    dirty = bool(run("git", "status", "--porcelain", capture=True))
    if dirty and not args.allow_dirty:
        raise RuntimeError("Commit source changes first, or use --allow-dirty for development")
    commit = run("git", "rev-parse", "HEAD", capture=True).strip()
    epoch = int(run("git", "show", "-s", "--format=%ct", "HEAD", capture=True))
    version = ET.parse(ROOT / "pom.xml").getroot().find("{*}version").text
    if not version or any(c not in "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-" for c in version):
        raise RuntimeError("Unsafe or missing Maven project version")
    output = ROOT / "dist/releases" / (version + ("-dev" if dirty else ""))
    if output.exists():
        raise RuntimeError(f"Output already exists; move it before rebuilding: {output}")
    with tempfile.TemporaryDirectory(prefix="gitlines-release-") as workspace:
        work = Path(workspace)
        source = work / "source"
        source.mkdir()
        snapshot(source)
        artifacts = work / "artifacts"
        artifacts.mkdir()
        for target in targets:
            print(f"Building and testing {target}", flush=True)
            binary_dir = work / target
            binary_dir.mkdir()
            metadata = {"version": version, "commit": commit, "dirty": dirty,
                        "target": target, "toolchains": config,
                        "native_image_flags": ["--no-fallback", "-march=compatibility"]}
            if target == "macos-arm64":
                run("bash", "scripts/build.sh", cwd=source)
                shutil.copy2(source / "dist/gitlines", binary_dir / "gitlines")
                metadata["macos"] = run("sw_vers", capture=True).strip()
                metadata["sdk"] = run("xcrun", "--show-sdk-version", capture=True).strip()
                (binary_dir / "toolchain.txt").write_text(run("native-image", "--version", capture=True))
                (binary_dir / "libraries.txt").write_text(run("otool", "-L", str(binary_dir / "gitlines"), capture=True))
            else:
                arch = target.removeprefix("linux-")
                docker_arch = "amd64" if arch == "x86_64" else "arm64"
                run("docker", "buildx", "build", "--platform", f"linux/{docker_arch}",
                    "--build-arg", f"GRAALVM_IMAGE={config['linux_images'][arch]}",
                    "--build-arg", f"MAVEN_VERSION={config['maven_version']}",
                    "--file", str(source / "packaging/Dockerfile"),
                    "--output", f"type=local,dest={binary_dir}", str(source))
            package(binary_dir, artifacts, target, version, metadata, epoch)
        archives = sorted(artifacts.glob("*.tar.gz"))
        (artifacts / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in archives))
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(artifacts), output)
    print(f"Tested release packages: {output}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"error: {error}")
