# opencode on Termux (Android / aarch64)

How to install [OpenCode](https://opencode.ai) — the open-source AI coding agent — **natively** inside Termux, with no proot, no glibc-runner and no patchelf shims. Updates arrive through `pkg upgrade` like any other Termux package.

Verified on the device this repo was written on:

| | |
|---|---|
| Platform | Termux on Android 15 (API 35) |
| Architecture | `aarch64` |
| Termux prefix | `/data/data/com.termux/files/usr` |
| Installed | `opencode v2.3.2` (native Bionic, ~209 MB) |
| Command | `$PREFIX/bin/opencode` |

## Quick install

```bash
curl -fsSL https://raw.githubusercontent.com/MBanucu/opencode-termux-setup/main/install.sh | bash
```

Or clone and run it:

```bash
git clone https://github.com/MBanucu/opencode-termux-setup.git
cd opencode-termux-setup
bash install.sh
```

### Or tap a button (Android app)

`android/` contains a small setup app in the same shape as the one in [freebuff-native](https://github.com/MBanucu/freebuff-native-termux): it bundles nothing, shows what will be downloaded, and hands the single command above to Termux through `RUN_COMMAND`. Install the APK from [Releases](../../releases/latest), tap **Install opencode**, and its output streams onto that page line by line — under a pseudo terminal, progress bars and colours included — while Termux runs it in the background. **Uninstall opencode** opens a page where you choose whether to keep your credentials, sessions and settings or delete them along with the package and its APT repository. Both actions are pages rather than dialogs, and whichever one you start prints that command's output from Termux on the same page.

It needs Termux's `allow-external-apps=true` in `~/.termux/termux.properties`; if Termux refuses the command, the app copies that line to the clipboard.

### Or from adb

Every action the app offers is scriptable, so it can run headless:

```bash
adb shell am start -n io.github.mbanucu.opencodesetup/.AdbCommandActivity -e command check
adb shell cat /sdcard/Android/data/io.github.mbanucu.opencodesetup/files/last-result.txt
# opencode v2.3.2
# exit code 0

adb shell am start -n io.github.mbanucu.opencodesetup/.AdbCommandActivity \
  -e command uninstall --ez purge true
```

`-e command` takes `install`, `uninstall` or `check`; `--ez purge true` makes an
uninstall delete credentials, sessions and settings instead of keeping them.
`check` fills `last-result.txt` with Termux's real answer, while install and
uninstall report that the command was sent - they run visibly in Termux, which
writes its output to the same file when it finishes.

### Building and testing on the device

The workflow in `.github/workflows/android.yml` is still the compiler of record, but a change can be built and checked here without pushing:

```bash
pkg install aapt2                 # Google only ships aapt2 for x86_64
bash test/local-build.sh          # build, then the app and sample suites
```

`test/local-build.sh` finds the SDK, writes the git-ignored `android/local.properties`, points the Android Gradle Plugin at Termux's aarch64 `aapt2`, and hands both APKs to `test/android-e2e.sh`. It then builds the sample APKs and runs their checks from the same command, so every suite has one entry; `--skip-samples` leaves them out and `--samples` runs only them. Both APKs must be built by the same run: CI signs each one with its own throwaway debug keystore, so a locally built app paired with a CI test APK fails immediately with a permission denial. A local build is also not byte-identical to CI's, because aapt2 16.0.0.4 packages resources differently — the reproducible release job stays the authority for that.

On GitHub, the fast `F-Droid metadata` job runs on every pull request that touches `android/**`, `fdroiddata/**`, `test/**` or the workflow, and is the check to mark required in branch protection. The debug and reproducible release builds still run on pushes (including tags) and manual dispatch, never on pull requests. A version-bump PR normally names an `android-v*` tag that is created when the release is tagged, so the PR run prints a `warn` for a missing tag while the same state on `main` fails; run either mode locally with:

```bash
bash test/fdroid-metadata-test.sh                        # strict, as on main
ALLOW_MISSING_TAG=1 bash test/fdroid-metadata-test.sh    # as on a pull request
```

Every suite run is recorded instead of taking screenshots. `test/android-e2e.sh` streams the screen into the git-ignored `test/recordings/<run>.mp4` (`--no-record` skips it): `screenrecord` hands out raw H.264 on the device and ffmpeg writes it as a fragmented MP4 while the run is still going, with one short index pass at the end so the file can be seeked. It also writes `<run>.json` mapping each milestone a test marked with `TestMilestones.mark(...)` to its second in the video. Tests log the device's monotonic uptime and the harness reads the same clock when it starts recording, so a milestone's second is a subtraction rather than a guess. Nothing is extracted during a run: `python3 test/frames.py list --milestones test/recordings/<run>.json` prints the milestones, and `python3 test/frames.py extract --recording <run>.mp4 --milestones <run>.json --names installed --out $TMPDIR/frames` (or `--all`, `--at 12.5,40`) writes the PNGs when asked for. `python3 test/frames.py idle --recording <run>.mp4 --milestones <run>.json` goes the other way and lists the spans where nothing on screen moved, by subtracting the video from itself, so a slow stretch can be told apart from a slow test. Each still span is reported as either work — inside a window a test declares with `TestMilestones.busy(...)`, like the real uninstall and install — or latency, the wait between an action and the page it opens; only the second is time the app could save.

F-Droid packaging lives in [`fdroiddata/`](fdroiddata/): the metadata recipe, the submission notes, and the reproducibility settings the release build uses.

Then:

```bash
opencode auth      # connect a model provider
cd ~/my-project
opencode           # start the TUI
```

## Why the official install commands do not work here

Upstream only publishes **glibc** and **musl** Linux binaries. Termux runs on Android's **Bionic libc**, so every "normal" method fails on this device:

| Method | Result on Termux |
|---|---|
| `curl -fsSL opencode.ai/v2/install \| bash` | Downloads a glibc binary, then `cannot execute: required file not found` (interpreter `/lib/ld-linux-aarch64.so.1` does not exist) |
| `npm install -g @opencode/cli` | `EBADPLATFORM` — the package declares `os: darwin,linux,win32`, and `android` is not in the list |
| `npm install -g opencode-ai` | Postinstall looks for `opencode-android-arm64`, which is never published |
| `brew` / `pacman` / `scoop` | Not available in Termux |

Forcing it through `--force`, or wrapping the glibc binary, works only partially and breaks on the next `opencode upgrade`.

Also note that `opencode` is **not in the official Termux repositories yet** — `termux-main`, `termux-glibc` and `tur-repo` carry no such package, so on a stock Termux `pkg install opencode` fails with `E: Unable to locate package opencode`. Upstream packaging is in review: [termux/termux-packages#32643 `addpkg(main/opencode)`](https://github.com/termux/termux-packages/pull/32643) (open since 2026-10-15, awaiting review; tracking issue [#25387](https://github.com/termux/termux-packages/issues/25387)). If that PR is merged and built, the third-party source below is no longer needed.

## What actually works: a native Bionic package

[bd-loser/opencode-bionic](https://github.com/bd-loser/opencode-bionic) builds OpenCode for Android/aarch64 against Bionic and publishes it in a **signed apt repository**, with `stable`, `v1` (legacy 1.x line) and `dev` channels. That is what `install.sh` uses.

Manual equivalent, if you would rather see every step:

```bash
mkdir -p "$PREFIX/etc/apt/trusted.gpg.d" "$PREFIX/etc/apt/sources.list.d"

curl -fsSL https://bd-loser.github.io/opencode-bionic/opencode-bionic.gpg \
  -o "$PREFIX/etc/apt/trusted.gpg.d/opencode-bionic.gpg"

echo "deb [arch=aarch64] https://bd-loser.github.io/opencode-bionic stable main" \
  > "$PREFIX/etc/apt/sources.list.d/opencode-bionic.list"

pkg update -y
pkg install -y opencode
opencode --version    # opencode v2.3.2
```

## Daily use

```bash
opencode auth                 # store provider credentials
opencode models               # list available models
opencode                      # TUI in the current directory
opencode run "explain this repo"
opencode session list
```

## Update, switch channel, remove

```bash
pkg upgrade opencode                                   # update in place
bash install.sh v1                                     # legacy 1.x line
bash install.sh dev                                     # bleeding edge
pkg uninstall opencode                                 # remove the program
rm "$PREFIX/etc/apt/sources.list.d/opencode-bionic.list"   # optional: drop the repo
```

## Troubleshooting

**`Waiting for cache lock ... held by process N (apt)`** — another `apt`/`pkg` run still holds the dpkg lock (a background update, an interrupted install). Wait for it, or check and repair:

```bash
ps -ef | grep -E 'apt |dpkg' | grep -v grep
dpkg --configure -a
```

**`pkg install` says the package is unavailable** — the repo list was not read. Re-run `pkg update -y`, and confirm `$PREFIX/etc/apt/sources.list.d/opencode-bionic.list` exists.

**`cannot execute: required file not found` after install** — an older glibc install is still shadowing the native one in `PATH` (`~/.opencode/bin/opencode`, added to `~/.bashrc` by the official script). Remove that directory and the line pointing to it, then run `hash -r`.

**Running from `/sdcard`** — shared storage is mounted `noexec`; keep projects under `$HOME`.

## Other options on Android

| Approach | Trade-off |
|---|---|
| This repo — native Bionic apt package | Fast, no chroot, updates via `pkg`; build comes from a community project |
| `proot-distro install debian`, then the official installer inside | Upstream binaries, larger and slower, files live inside the rootfs |
| `@opencode/cli` with `--force` | Bypasses platform checks and the generated wrapper needs manual fixes |
| SSH from Termux into a Linux box | Official builds, needs another machine |

## Credits

The packaging work belongs to [bd-loser/opencode-bionic](https://github.com/bd-loser/opencode-bionic) (MIT). OpenCode itself is [anomalyco/opencode](https://github.com/anomalyco/opencode) (MIT). The app's terminal is [xterm.js](https://xtermjs.org) 5.5.0 with the fit addon, vendored under `android/app/src/main/assets/xterm/` (MIT). This repo only documents and automates the install for this device.

## Terminal view

The native accessible terminal view lives in its own repository,
[MBanucu/android-accessible-terminal](https://github.com/MBanucu/android-accessible-terminal),
under **GPL-3.0-or-later**, and is published to Maven Central as
`io.github.mbanucu:android-accessible-terminal` (0.2.0 is the first release there).
The app does not link it yet: [`docs/terminal-module-roadmap.md`](docs/terminal-module-roadmap.md)
is the plan for replacing the xterm.js page with it. When it does, that repository
at the pinned version is the corresponding source for the library.

## Licence

This repository — `install.sh`, `uninstall.sh`, the documentation and the Android app under `android/` — is **GPL-3.0-or-later**. The full text is in [`LICENSE`](LICENSE). opencode and the packages built by bd-loser/opencode-bionic keep their own MIT licence; neither is redistributed here.
