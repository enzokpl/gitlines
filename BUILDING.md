# Building and distributing gitlines

This guide describes the development build and the local workflow for producing
tested native release packages. It is intended for project maintainers.

The distributed executable requires Git in `PATH`; users do not need Java,
GraalVM, or Docker.

## Local release workflow

On an Apple Silicon Mac, generate macOS ARM64, Linux ARM64, and Linux x86_64
packages with one command:

```sh
python3 scripts/release.py --check
python3 scripts/release.py
```

Requirements:

- Python 3.12 or newer for the release script's safe archive extraction.
- Git, a running Docker engine, and Docker Buildx with Linux ARM64/AMD64 emulation.
  OrbStack or Docker Desktop can provide the Linux environment locally.
- The macOS GraalVM and Maven versions specified in `packaging/toolchains.json`.
- Xcode command-line tools and a selected macOS SDK (`xcrun --show-sdk-path`).
- Network access for the pinned container images, Maven, OS packages, and Java dependencies.

Successful output appears in `dist/releases/<version>/`:

```text
gitlines-0.1.0-macos-arm64.tar.gz
gitlines-0.1.0-linux-arm64.tar.gz
gitlines-0.1.0-linux-x86_64.tar.gz
SHA256SUMS
```

Release builds require a clean Git working tree. During development:

```sh
python3 scripts/release.py --allow-dirty
python3 scripts/release.py --allow-dirty --target macos-arm64
```

- Development packages go into `<version>-dev` and record `dirty: true`; their commit
identifier alone does not identify all source changes.
- `--target` can be repeated.

## Toolchain versions and platform compatibility

Linux GraalVM images are locked to architecture-specific digests on Oracle Linux 8.
Maven is versioned and its downloaded archive is checked against Apache's SHA-512.
The host macOS GraalVM is checked against an exact native-image/runtime version.
Both build paths use `--no-fallback -march=compatibility`.

This is a repeatable build procedure, not a promise of byte-identical reproducible
binaries. Linux package repositories, Maven's transitive dependencies, the local
Apple SDK, signing, and archive compression can affect output. Build metadata
records the commit, toolchain configuration, and macOS SDK. Linux and macOS use
different explicitly recorded GraalVM versions; update the lock file deliberately.

Linux binaries use glibc (the Oracle Linux 8 baseline is glibc 2.28); Alpine/musl
is not supported. Inspect each package's `libraries.txt` for actual dependencies.
The suite runs in the build environment; compatibility on other distributions,
older macOS versions, and physical x86_64 machines requires additional validation.
macOS packages are not Developer ID signed or notarized by this workflow and can
trigger Gatekeeper checks when downloaded. No minimum macOS version is promised
yet. Windows and macOS Intel are not generated.

## Verifying and installing release packages

Verify the generated or downloaded archives on macOS with:

```sh
shasum -a 256 -c SHA256SUMS
```

Or on Linux with `sha256sum -c SHA256SUMS`. Download every archive listed in the
checksum file to verify the entire set. Checksums verify integrity, not publisher
identity; this local workflow does not generate GitHub build attestations.

To install, extract the matching archive, enter its directory, and run:

```sh
mkdir -p ~/.local/bin
cp gitlines ~/.local/bin/gitlines
```

Add `~/.local/bin` to your shell's `PATH` and run `gitlines --version`.

## Development build

With Java 25, GraalVM Native Image, Maven, a C compiler, Git, and Python installed:

```sh
./scripts/build.sh
```

The resulting executable is `dist/gitlines`. The release workflow builds in a
separate source snapshot and does not replace this development executable.
