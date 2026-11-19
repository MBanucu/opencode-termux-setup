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

`android/` contains a small setup app in the same shape as the one in [freebuff-native](https://github.com/MBanucu/freebuff-native-termux): it bundles nothing, shows what will be downloaded, and hands the single command above to Termux through `RUN_COMMAND`. Install the APK from [Releases](../../releases/latest), tap **Install opencode**, and watch it run in the Termux window. **Uninstall opencode** reverses it — package and APT repository — while keeping your credentials and sessions.

It needs Termux's `allow-external-apps=true` in `~/.termux/termux.properties`; if Termux refuses the command, the app copies that line to the clipboard.

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

The packaging work belongs to [bd-loser/opencode-bionic](https://github.com/bd-loser/opencode-bionic) (MIT). OpenCode itself is [anomalyco/opencode](https://github.com/anomalyco/opencode) (MIT). This repo only documents and automates the install for this device.
