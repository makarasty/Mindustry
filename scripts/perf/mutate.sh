#!/usr/bin/env bash
# Checks that ab.sh can see a bug: breaks an optimization and expects a hash mismatch or an opt-only crash.
#
#   BASEJAR=base.jar OPTJAR=opt.jar scripts/perf/mutate.sh <save> <ticks> <work-relative file> <old> <new> [<old> <new>...]
#
# <old>/<new> are literal strings (\n allowed, ASCII only); each <old> must occur exactly once in the file.
# Several pairs break redundant guards together. The mutant is OPTJAR with that one file recompiled.
# Pass the chaos actions that exercise the code via HASH_OPTS.
set -eo pipefail
PERF="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$PERF/../.." && pwd)"
RUN="$PERF/run"
J="${JAVA_HOME:+$JAVA_HOME/bin/}"
sep=":"; wp(){ echo "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; wp(){ cygpath -w "$1"; };; esac
abs(){ echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
#first python that actually runs (on Windows python3 may be the Microsoft Store stub)
PY=""; for c in python3 python; do command -v $c >/dev/null && $c -c "" 2>/dev/null && { PY=$c; break; }; done
[ -n "$PY" ] || { echo "python not found" >&2; exit 1; }

[ $# -ge 5 ] && [ $(( ($# - 3) % 2 )) -eq 0 ] || { echo "usage: $0 <save> <ticks> <file> <old> <new> [<old> <new>...]" >&2; exit 1; }
save="$(abs "$1")"; ticks=$2; file=$3; shift 3
opt="$(abs "${OPTJAR:?set OPTJAR to the patched server jar}")"
mkdir -p "$RUN/mutant" && cd "$RUN/mutant"
rm -rf src cls && mkdir -p src cls

#no MSYS path conversion of the anchor arguments
MSYS_NO_PATHCONV=1 "$PY" - "$(wp "$ROOT/work/$file")" "src/$(basename "$file")" "$@" <<'PY'
import sys
f, out, pairs = sys.argv[1], sys.argv[2], sys.argv[3:]
s = open(f, encoding='utf8').read()
for old, new in zip(pairs[0::2], pairs[1::2]):
    old = old.encode().decode('unicode_escape'); new = new.encode().decode('unicode_escape')
    assert s.count(old) == 1, 'anchor must occur exactly once: ' + old
    s = s.replace(old, new)
open(out, 'w', encoding='utf8', newline='\n').write(s)
PY

"$J"javac -nowarn -proc:none -encoding UTF-8 -d cls -cp "$(wp "$opt")$sep$(wp "$ROOT/work/annotations/build/classes/java/main")" src/*.java
cp "$opt" mutant.jar
(cd cls && "$J"jar uf ../mutant.jar $(find . -name '*.class' | sed 's#^[.]/##'))

code=0
OPTJAR="$RUN/mutant/mutant.jar" "$PERF/ab.sh" "$save" "$ticks" 0 || code=$?
case $code in
  0) echo "MUTANT SURVIVED: the check does not cover this code path"; exit 1;;
  2|3) echo "mutant killed";;
  *) echo "inconclusive: ab.sh exited with $code"; exit 1;;
esac
