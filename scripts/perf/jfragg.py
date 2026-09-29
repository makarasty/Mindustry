"""Group JFR execution samples of the server main thread by the block updateTile they run under.

usage: python jfragg.py <jdk>/bin/jfr <recording.jfr> [leaf substrings to show callers for...]
"""
import collections
import re
import subprocess
import sys

jfr, jfile = sys.argv[1], sys.argv[2]
res = subprocess.run([jfr, "print", "--events", "jdk.ExecutionSample", "--stack-depth", "64", jfile],
                     capture_output=True, text=True, encoding="utf8", errors="replace")
if res.returncode != 0:
    sys.exit(res.stderr)

owner = collections.Counter()
caller = collections.Counter()
total = 0
# class names may contain '/' (hidden lambda classes); <init>/<clinit> are frames too
frame_re = re.compile(r"^\s+([\w.$/]+(?:\.<\w+>)?)\(")
for ev in res.stdout.split("jdk.ExecutionSample"):
    # the main thread is named HeadlessApplication
    if '"HeadlessApplication"' not in ev:
        continue
    frames = [m.group(1) for m in (frame_re.match(line) for line in ev.splitlines()) if m]
    if not frames:
        continue
    total += 1
    tag = next((f for f in frames if re.search(r"\$\w+Build\.(updateTile|update)$", f) or f.endswith("PowerGraph.update")), None)
    owner[tag or "OTHER:" + frames[0]] += 1
    if any(k in frames[0] for k in sys.argv[3:]):
        caller[" <- ".join(frames[:4])] += 1

print("main-thread samples:", total)
for k, v in owner.most_common(40):
    print(f"{v * 100 / max(total, 1):6.2f}%  {k}")
if caller:
    print("---")
    for k, v in caller.most_common(25):
        print(f"{v:5d}  {k}")
