<p align="center">
  <img src="assets/gitlines-logo.png" alt="Gitlines logo" width="280">
</p>

# Gitlines

**Turn your Git history into a clear contribution report.**

Gitlines is a native command-line tool that shows commits, lines added, lines deleted,
and net changes for each author. Get a quick overview in your terminal, share a
standalone HTML report, or export JSON for your own tools.

Everything runs locally. The executable only needs Git installed—no Java runtime
or Docker required.

## Install

Download the archive for your platform from [Releases](https://github.com/enzokpl/gitlines/releases):

| Platform | Archive suffix |
| --- | --- |
| macOS, Apple Silicon | `macos-arm64.tar.gz` |
| Linux, ARM64 | `linux-arm64.tar.gz` |
| Linux, Intel / AMD 64-bit | `linux-x86_64.tar.gz` |

For example, installing version `0.1.0` on an Apple Silicon Mac:

```sh
tar -xzf gitlines-0.1.0-macos-arm64.tar.gz
mkdir -p ~/.local/bin
cp gitlines-0.1.0-macos-arm64/gitlines ~/.local/bin/gitlines
```

Add `~/.local/bin` to your shell's `PATH`, then check the installation:

```sh
export PATH="$HOME/.local/bin:$PATH"
gitlines --version
```

To keep that PATH setting, add the `export` line to your `~/.zshrc` or `~/.bashrc`.
See [BUILDING.md](BUILDING.md) for platform compatibility, macOS signing status,
and instructions to build from source.

## Use

Run inside a Git repository:

```sh
gitlines
```

Or point it at another repository:

```sh
gitlines ~/projects/my-app
```

Example contributor table (illustrative):

```text
Author <email>              Commits     Added   Deleted       Net
----------------------------------------------------------------
Alice <alice@example.com>        24     3,120       840    +2,280
Bob <bob@example.com>            16     1,450       620      +830
```

Create an HTML report you can open in a browser or share as a single file:

```sh
gitlines ~/projects/my-app --html report.html
```

Export JSON, or generate both formats in one run:

```sh
gitlines --json report.json
gitlines --html report.html --json report.json
```

The terminal summary is also printed when exporting. Existing report files are
replaced.

For additional options:

```sh
gitlines --help
gitlines --workers 4
```

`--workers` accepts `1` through `4` and defaults to `2`.

## What the numbers mean

Gitlines analyzes non-merge commits reachable from the current `HEAD`, respects
Git's `.mailmap`, and sorts authors by lines added plus deleted. Net changes are
added lines minus deleted lines. Binary files do not contribute line counts.

These are historical changes, not ownership of the current files or a measure of
code quality. For complete results, use a full clone rather than a shallow clone.

## License

[MIT](LICENSE) © 2026 Enzo Capello Ribeiro.
