"""Self and inclusive time of the hottest methods in a JFR recording, per thread group.

usage: python jfrtop.py <jdk>/bin/jfr <recording.jfr> [threadSubstring] [n]
Allocation samples (jdk.ObjectAllocationSample) are summed by allocation site with the same frame filter.
"""
import collections
import re
import subprocess
import sys

jfr, jfile = sys.argv[1], sys.argv[2]
thread = sys.argv[3] if len(sys.argv) > 3 else "HeadlessApplication"
n = int(sys.argv[4]) if len(sys.argv) > 4 else 30
frame_re = re.compile(r"^\s+([\w.$/]+(?:\.<\w+>)?)\(")


def events(kind):
    res = subprocess.run([jfr, "print", "--events", kind, "--stack-depth", "64", jfile],
                         capture_output=True, text=True, encoding="utf8", errors="replace")
    if res.returncode != 0:
        sys.exit(res.stderr)
    return res.stdout.split(kind)[1:]


selfc, incl = collections.Counter(), collections.Counter()
total = 0
for ev in events("jdk.ExecutionSample"):
    if thread not in ev:
        continue
    frames = [m.group(1) for m in (frame_re.match(l) for l in ev.splitlines()) if m]
    if not frames:
        continue
    total += 1
    selfc[frames[0]] += 1
    for f in set(frames):
        incl[f] += 1
print(f"cpu samples on '{thread}': {total}")
print("--- self")
for k, v in selfc.most_common(n):
    print(f"{v * 100 / max(total, 1):6.2f}%  {k}")
print("--- inclusive")
for k, v in incl.most_common(n * 2):
    print(f"{v * 100 / max(total, 1):6.2f}%  {k}")

alloc = collections.Counter()
weight_re = re.compile(r"weight = ([\d.]+) (\w+)")
for ev in events("jdk.ObjectAllocationSample"):
    if thread not in ev:
        continue
    frames = [m.group(1) for m in (frame_re.match(l) for l in ev.splitlines()) if m]
    w = weight_re.search(ev)
    if not frames or not w:
        continue
    mult = {"bytes": 1, "kB": 1e3, "MB": 1e6, "GB": 1e9}.get(w.group(2), 1)
    alloc[" <- ".join(frames[:3])] += float(w.group(1)) * mult
if alloc:
    s = sum(alloc.values())
    print(f"--- allocation by site ({s / 1e6:.0f} MB sampled)")
    for k, v in alloc.most_common(15):
        print(f"{v * 100 / s:6.2f}%  {k}")

# callers: python jfrtop.py jfr rec thread n callers <frameSubstring> -> who calls into it, first frame outside arc.*/Units/QuadTree
if len(sys.argv) > 6 and sys.argv[5] == "callers":
    key = sys.argv[6]
    who = collections.Counter()
    hits = 0
    for ev in events("jdk.ExecutionSample"):
        if thread not in ev:
            continue
        frames = [m.group(1) for m in (frame_re.match(l) for l in ev.splitlines()) if m]
        idx = next((i for i, f in enumerate(frames) if key in f), None)
        if idx is None:
            continue
        hits += 1
        rest = [f for f in frames[idx + 1:] if not (f.startswith("arc.") or "Units." in f or "lambda$" in f or "QuadTree" in f)]
        who[rest[0] if rest else "?"] += 1
    print(f"--- callers of {key}: {hits} samples")
    for k, v in who.most_common(20):
        print(f"{v * 100 / max(total, 1):6.2f}%  {k}")
