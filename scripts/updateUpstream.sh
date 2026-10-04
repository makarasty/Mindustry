#!/usr/bin/env bash

set -e

cd "$(dirname "$0")/.." || exit 1   # every path below is relative to the repo root

function getRef() {
    git ls-tree "$1" "$2" | cut -d' ' -f3 | cut -f1
}

upstreamRef="$1"
# no ref given: take the newest upstream release tag, never the unreleased HEAD
# (minGameVersion and the loader mod download both need a real release number)
if [ -z "$upstreamRef" ]; then
  upstreamRef=$(git -C work ls-remote --tags --refs origin 'v*' | sed 's#.*refs/tags/##' | sort -V | tail -1)
  [ -n "$upstreamRef" ] || { echo "Cannot find the latest upstream release tag"; exit 1; }
  echo "No ref given, using latest release $upstreamRef"
fi

refHEAD_Arc=$(getRef HEAD Arc || exit 1)
cd Arc && git fetch origin "$upstreamRef" || exit 1
refRemote_Arc=$(git rev-parse FETCH_HEAD || exit 1)
cd ..

refHEAD=$(getRef HEAD work || exit 1)
cd work && git fetch origin "$upstreamRef" || exit 1
refRemote=$(git rev-parse FETCH_HEAD || exit 1)
cd ..

# true when <repo> has at least as many commits on top of <marker> as <dir> has patches (own extra
# commits count, so they are never reset away), and the marker sits on <pin>
function patched() { # repo pin marker dir
    local marker
    marker=$(git -C "$1" log -1 --grep "$3" --format=%H)
    [ -n "$marker" ] && git -C "$1" merge-base --is-ancestor "$2" "$marker" &&
        [ "$(git -C "$1" rev-list --count "$marker..HEAD")" -ge "$(ls "$4" | wc -l)" ]
}

if [ "$refHEAD_Arc" == "$refRemote_Arc" ] && [ "$refHEAD" == "$refRemote" ]; then
  # an earlier run may have committed the pins and died while patching: finish it instead of exiting
  if patched Arc "$refHEAD_Arc" '#PATCH-BASE#' patches/arc && patched work "$refHEAD" '#END-PICKED#' patches/client; then
    echo "No update in both Arc and work"
    exit
  fi
  echo "No upstream update, but the patches are not fully applied: rebuilding them"
else
  echo "Arc $refHEAD_Arc -> $refRemote_Arc"
  echo "Work $refHEAD -> $refRemote"
  (cd Arc && git reset --hard FETCH_HEAD || (echo "Fail reset Arc" && exit 1))
  (cd work && git reset --hard FETCH_HEAD || (echo "Fail reset work" && exit 1))

  version=$(echo "$upstreamRef" | sed 's/^v//')
  # only a release number is a valid minGameVersion; HEAD, a branch or a sha keeps the old one
  if [[ "$version" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
    sed -i "s/minGameVersion: \".*\"/minGameVersion: \"$version\"/" assets/mod.hjson
  else
    echo "Ref $upstreamRef is not a release, keeping minGameVersion in assets/mod.hjson"
  fi

  git add --force Arc work assets/mod.hjson && git commit -m "Update HEAD -> $upstreamRef($refRemote)"
fi

echo "Rebuilding patches"
./scripts/applyPatches.sh
./scripts/genPatches.sh

echo
echo "Syncing packet order"
gradle=gradle; command -v gradle >/dev/null || gradle=./gradlew
(cd work && $gradle -q :core:syncPackets)
git --no-pager diff -- assets/packets.jsonl