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
        --milestones test/recordings/1712345678.json
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


def load_milestones(path):
    with open(path) as handle:
        data = json.load(handle)
    return data.get("milestones", [])


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


def where(at, moments):
    """What was on screen at that second, said with the milestones around it."""
    before = [moment for moment in moments if moment["seconds"] <= at]
    after = [moment for moment in moments if moment["seconds"] > at]
    label = "after " + before[-1]["name"] if before else "before the first milestone"
    if after:
        label += ", before " + after[0]["name"]
    return label


def report_idle(recording, moments, threshold, minimum, fps):
    series, spans = idle_spans(recording, threshold, minimum, fps)
    length = series[-1][0] if series else 0.0
    print("  recording: %s" % recording)
    print("  %d frames at %d fps, still for at least %.1fs, mean difference below %.2f"
          % (len(series), fps, minimum, threshold))
    for start, end in spans:
        print("  %7.2f-%7.2fs  %5.2fs  %s"
              % (start, end, end - start, where(start, moments)))
    idle = sum(end - start for start, end in spans)
    if length:
        print("  %.1fs still of %.1fs (%.0f%%) in %d span(s)"
              % (idle, length, 100.0 * idle / length, len(spans)))


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
        report_idle(args.recording,
                    load_milestones(args.milestones) if args.milestones else [],
                    args.threshold, args.minimum, args.fps)
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
