#!/usr/bin/env python3
"""Test timings in SQLite, with an estimate for the run in progress.

The instrumentation logs one line per finished test, on the app's tag:

    timing: <class>#<test> <millis>ms <passed|failed>

This script keeps those lines in a SQLite file, which answers the two questions
the harness has: how these tests have behaved over time (`history`), and how much
longer the run that is happening now needs (`eta`). The estimate is the median of
each remaining test's past durations, plus what the run has already spent, so a
slow mirror makes the estimate larger instead of making a test fail.

    python3 test/timings.py record  --log run.log --run 1699999999
    python3 test/timings.py eta     --class <class> --run 1699999999 --log run.log
    python3 test/timings.py history [--class <class>]

The database defaults to test/.timings.db beside this script and can be queried
with any SQLite client.
"""

import argparse
import os
import re
import sqlite3
import statistics
import sys
from datetime import datetime, timezone

LINE = re.compile(r"timing: (\S+)#(\S+) (\d+)ms (passed|failed)")
DEFAULT_DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".timings.db")
SCHEMA = """
CREATE TABLE IF NOT EXISTS timings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run TEXT NOT NULL,
    class TEXT NOT NULL,
    test TEXT NOT NULL,
    outcome TEXT NOT NULL,
    millis INTEGER NOT NULL,
    recorded_at TEXT NOT NULL,
    UNIQUE (run, test)
);
CREATE INDEX IF NOT EXISTS timings_by_test ON timings (class, test);
"""


def connect(path):
    db = sqlite3.connect(path)
    db.executescript(SCHEMA)
    return db


def ingest(db, log, run):
    """Adds the timings in `log` that this database has not seen yet."""
    if not log or not os.path.isfile(log):
        return 0
    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    added = 0
    with open(log, errors="replace") as stream:
        for line in stream:
            found = LINE.search(line)
            if found:
                added += db.execute(
                    "INSERT OR IGNORE INTO timings"
                    " (run, class, test, outcome, millis, recorded_at)"
                    " VALUES (?, ?, ?, ?, ?, ?)",
                    (run, found.group(1), found.group(2), found.group(4),
                     int(found.group(3)), stamp),
                ).rowcount
    db.commit()
    return added


def median_of(values):
    return int(statistics.median(values)) if values else None


def seconds(millis):
    return "%.1fs" % (millis / 1000.0)


def plan(db, name, run):
    """The tests of this class, what the run has done, and what is left."""
    tests = [row[0] for row in db.execute(
        "SELECT DISTINCT test FROM timings WHERE class = ? ORDER BY test", (name,))]
    done = {row[0]: row[1] for row in db.execute(
        "SELECT test, millis FROM timings WHERE class = ? AND run = ?", (name, run))}
    overall = median_of([row[0] for row in db.execute(
        "SELECT millis FROM timings WHERE class = ?", (name,))])
    left = 0
    for test in tests:
        if test in done:
            continue
        past = [row[0] for row in db.execute(
            "SELECT millis FROM timings WHERE class = ? AND test = ? AND run <> ?",
            (name, test, run))]
        left += median_of(past) or overall or 10_000
    runs = db.execute(
        "SELECT COUNT(DISTINCT run) FROM timings WHERE class = ?", (name,)).fetchone()[0]
    return tests, done, left, runs


def report_record(args):
    db = connect(args.db)
    print("recorded %d timing(s) for run %s" % (ingest(db, args.log, args.run), args.run))
    return 0


def report_eta(args):
    db = connect(args.db)
    ingest(db, args.log, args.run)
    tests, done, left, runs = plan(db, args.class_, args.run)
    if not tests:
        print("eta: no timing history yet for %s" % args.class_)
        return 0
    spent = sum(done.values())
    print("eta: %s spent, %s left, %s total, %d/%d done, %d run(s) of history"
          % (seconds(spent), seconds(left), seconds(spent + left),
             len(done), len(tests), runs))
    return 0


def report_history(args):
    db = connect(args.db)
    query = ("SELECT class, test, millis, outcome FROM timings"
             + (" WHERE class = ?" if args.class_ else "")
             + " ORDER BY class, test, id")
    rows = db.execute(query, (args.class_,) if args.class_ else ())
    grouped = {}
    for room, test, millis, outcome in rows:
        entry = grouped.setdefault((room, test), {"millis": [], "outcome": outcome})
        entry["millis"].append(millis)
    if not grouped:
        print("no timings recorded yet")
        return 0
    print("%-44s %4s %8s %8s %8s %8s  %s"
          % ("test", "runs", "median", "p90", "min", "max", "last"))
    for (room, test), entry in grouped.items():
        values = sorted(entry["millis"])
        p90 = values[min(len(values) - 1, round(0.9 * (len(values) - 1)))]
        print("%-44s %4d %8s %8s %8s %8s  %s"
              % (test, len(values), seconds(statistics.median(values)), seconds(p90),
                 seconds(values[0]), seconds(values[-1]), entry["outcome"]))
    return 0


def main(argv):
    # --db is accepted on either side of the subcommand: before it here, after it
    # through `common`, where SUPPRESS keeps the inner default from wiping the
    # outer one when the flag is only given once.
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--db", default=argparse.SUPPRESS)

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--db", default=DEFAULT_DB, help="SQLite file to use")
    sub = parser.add_subparsers(dest="command", required=True)

    record = sub.add_parser("record", parents=[common],
                            help="store the timings found in a log")
    record.add_argument("--log", required=True)
    record.add_argument("--run", required=True)
    record.set_defaults(handler=report_record)

    eta = sub.add_parser("eta", parents=[common],
                         help="estimate how long the run in progress needs")
    eta.add_argument("--class", dest="class_", required=True)
    eta.add_argument("--run", required=True)
    eta.add_argument("--log", default="")
    eta.set_defaults(handler=report_eta)

    history = sub.add_parser("history", parents=[common],
                             help="per-test durations recorded so far")
    history.add_argument("--class", dest="class_", default="")
    history.set_defaults(handler=report_history)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
