#!/usr/bin/env python3
"""Frames out of a suite recording, when somebody asks for them.

The suite records the screen for a run (``test/recordings/<run>.mp4``) and writes
a sidecar (``<run>.json``) that maps every milestone a test marked to its offset
into that video. Nothing is extracted while the tests run; this script is the
"when asked" part:

    python3 test/frames.py list --milestones test/recordings/1712345678.json
    python3 test/frames.py extract --recording test/recordings/1712345678.mp4 \\
        --milestones test/recordings/1712345678.json --out "$TMPDIR/frames"
    python3 test/frames.py extract --recording run.mp4 --at 12.5,40 --out /tmp

``--all`` takes a frame at every milestone, ``--names a,b`` picks a few, and
``--at`` takes explicit seconds. Frames are PNGs named after the second they were
taken at, or after the milestone when one matches.

``idle`` answers the other question a recording can be asked - where nothing on
screen moved - by subtracting the video from itself:

    python3 test/frames.py idle --recording test/recordings/1712345678.mp4 \\
        --milestones test/recordings/1712345678.json --only latency

Every still span is reported as either work (inside a window a test declared
with ``TestMilestones.busy``, such as the real uninstall and install) or latency
(the wait between an action and the page it opens). Only the second is time the
app could save.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys


def ffmpeg():
    exe = shutil.which("ffmpeg")
    if not exe:
        sys.exit("ffmpeg not found; on Termux: pkg install ffmpeg")
    return exe


def load_sidecar(path):
    with open(path) as handle:
        return json.load(handle)


def load_milestones(path):
    return load_sidecar(path).get("milestones", [])


def pick(milestones, names, take_all):
    if take_all:
        return milestones
    wanted = [name.strip() for name in (names or "").split(",") if name.strip()]
    missing = [name for name in wanted if all(m["name"] != name for m in milestones)]
    if missing:
        sys.exit("no milestone named: " + ", ".join(missing))
    return [m for m in milestones if m["name"] in wanted]


def extract(recording, at, out_dir, name):
    os.makedirs(out_dir, exist_ok=True)
    exe = ffmpeg()
    target = os.path.join(out_dir, name)
    command = [
        exe, "-hide_banner", "-loglevel", "error", "-y",
        "-ss", "%.3f" % at["seconds"], "-i", recording,
        "-frames:v", "1", target,
    ]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit("ffmpeg failed at %.2fs: %s" % (at["seconds"], result.stderr.strip()))
    size = os.path.getsize(target)
    print("  %-40s %7.2fs  %d bytes" % (os.path.basename(target), at["seconds"], size))
    return target


def idle_spans(recording, threshold, minimum, fps):
    """The spans of the video in which the picture did not change.

    The video is subtracted from itself: every sampled frame has the one before
    it removed (``tblend``) and the mean difference in luma is read back
    (``signalstats``). A still screen scores a few hundredths, a terminal that is
    writing scores tens to hundreds, so the threshold sits in the gap between the
    two rather than in either crowd.
    """
    command = [
        ffmpeg(), "-hide_banner", "-nostats", "-loglevel", "error",
        "-i", recording,
        "-vf", "fps=%d,scale=240:-2,tblend=all_mode=difference,signalstats,"
               "metadata=print:key=lavfi.signalstats.YAVG:file=-" % fps,
        "-f", "null", "-",
    ]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit("ffmpeg failed to read the recording: " + result.stderr.strip())

    series = []
    at = None
    for line in result.stdout.splitlines():
        header = re.match(r"frame:\d+\s+pts:\d+\s+pts_time:([0-9.]+)", line)
        if header:
            at = float(header.group(1))
            continue
        value = re.match(r"lavfi\.signalstats\.YAVG=([0-9.]+)", line)
        if value and at is not None:
            series.append((at, float(value.group(1))))

    step = 1.0 / fps
    spans = []
    start = None
    end = None
    for at, difference in series:
        if difference < threshold:
            start = at - step if start is None else start
            end = at
        elif start is not None:
            spans.append((start, end))
            start = None
    if start is not None:
        spans.append((start, end))
    return series, [(one, two) for one, two in spans if two - one >= minimum]


def work_of(span, busy):
    """The declared window a still span sits in, if it sits in one.

    A download or a removal is the app waiting on something it does not control:
    the screen is still, but the time is not the app's to save. Everything else
    that is still is a wait between an action and the page it opens.
    """
    middle = (span[0] + span[1]) / 2
    for window in busy:
        if window["start"] <= middle <= window["end"]:
            return window["name"]
    return None


def where(at, moments):
    """What was on screen at that second, said with the milestones around it."""
    before = [moment for moment in moments if moment["seconds"] <= at]
    after = [moment for moment in moments if moment["seconds"] > at]
    label = "after " + before[-1]["name"] if before else "before the first milestone"
    if after:
        label += ", before " + after[0]["name"]
    return label


def report_idle(recording, moments, busy, threshold, minimum, fps, only):
    series, spans = idle_spans(recording, threshold, minimum, fps)
    length = series[-1][0] if series else 0.0
    print("  recording: %s" % recording)
    print("  %d frames at %d fps, still for at least %.1fs, mean difference below %.2f"
          % (len(series), fps, minimum, threshold))
    rows = [(start, end, work_of((start, end), busy)) for start, end in spans]
    for start, end, work in rows:
        if only == "work" and not work:
            continue
        if only == "latency" and work:
            continue
        kind = ("work: " + work) if work else "latency"
        # The middle, not the start: a span that straddles a milestone belongs to
        # the side it mostly sits on.
        print("  %7.2f-%7.2fs  %5.2fs  %-22s %s"
              % (start, end, end - start, kind, where((start + end) / 2, moments)))
    if not length:
        return
    idle = sum(end - start for start, end, _ in rows)
    work = sum(end - start for start, end, named in rows if named)
    print("  %.1fs still of %.1fs (%.0f%%): %.1fs work in %d span(s), %.1fs latency in %d"
          % (idle, length, 100.0 * idle / length, work,
             sum(1 for _, _, named in rows if named), idle - work,
             sum(1 for _, _, named in rows if not named)))


def frame_name(moment):
    """A file name that says which moment it is, without collisions."""
    name = moment.get("name")
    if not name:
        return "at-%07.2f.png" % moment["seconds"]
    return name[:-4] if name.endswith(".png") else name + ".png"


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    listing = sub.add_parser("list", help="print the milestones a run recorded")
    listing.add_argument("--milestones", required=True)

    frame = sub.add_parser("extract", help="write PNG frames for chosen moments")
    frame.add_argument("--recording", required=True)
    frame.add_argument("--milestones", help="sidecar written by the suite")
    frame.add_argument("--names", help="comma separated milestone names")
    frame.add_argument("--all", action="store_true", help="every milestone")
    frame.add_argument("--at", help="comma separated seconds")
    frame.add_argument("--out", required=True)

    still = sub.add_parser("idle", help="list the spans where the screen did not change")
    still.add_argument("--recording", required=True)
    still.add_argument("--milestones", help="sidecar written by the suite, to name the spans")
    still.add_argument("--threshold", type=float, default=1.0,
                       help="mean luma difference below which a frame counts as still")
    still.add_argument("--min", dest="minimum", type=float, default=1.5,
                       help="shortest span to report, in seconds")
    still.add_argument("--fps", type=int, default=2, help="frames compared per second")
    still.add_argument("--only", choices=("all", "work", "latency"), default="all",
                       help="show every span, only the work, or only the waits")

    args = parser.parse_args()

    if args.command == "list":
        milestones = load_milestones(args.milestones)
        if not milestones:
            print("  no milestones recorded")
            return
        for moment in milestones:
            print("  %7.2fs  %s" % (moment["seconds"], moment["name"]))
        return

    if args.command == "idle":
        if not os.path.isfile(args.recording):
            sys.exit("no such recording: " + args.recording)
        sidecar = load_sidecar(args.milestones) if args.milestones else {}
        report_idle(args.recording, sidecar.get("milestones", []),
                    sidecar.get("busy", []), args.threshold, args.minimum,
                    args.fps, args.only)
        return

    if not os.path.isfile(args.recording):
        sys.exit("no such recording: " + args.recording)

    moments = []
    if args.milestones:
        moments.extend(pick(load_milestones(args.milestones), args.names, args.all))
    if args.at:
        for text in args.at.split(","):
            text = text.strip()
            if text:
                moments.append({"name": None, "seconds": float(text)})
    if not moments:
        sys.exit("nothing to extract: pass --all, --names or --at")

    print("  recording: %s" % args.recording)
    used = set()
    for moment in moments:
        # A milestone marked by several cases shares its name; keep every frame
        # rather than letting each one overwrite the last.
        name = frame_name(moment)
        stem, suffix = os.path.splitext(name)
        count = 2
        while name in used:
            name = "%s-%d%s" % (stem, count, suffix)
            count += 1
        used.add(name)
        extract(args.recording, moment, args.out, name)


if __name__ == "__main__":
    main()
