#!/usr/bin/env bash

set -e

cd "$(dirname "$0")/.." || exit 1   # every path below is relative to the repo root

# Arc Repository
cd Arc || { echo "Arc directory not found"; exit 1; }
base=$(git log --grep "#PATCH-BASE#" --format=reference | awk '{print $1}')
echo "Arc_BASE=$base"
# without the marker there is nothing to export and the rm below would wipe the patches
[ -n "$base" ] || { echo "no #PATCH-BASE# commit in Arc; run applyPatches.sh first"; exit 1; }
rm -rf ../patches/arc/*
git format-patch --full-index --no-signature --zero-commit -N --ignore-blank-lines -o ../patches/arc "$base..."
cd .. && git add patches/arc

# Main Repository
cd work || { echo "work directory not found"; exit 1; }
base=$(git log --grep "#PATCH-BASE#" --format=reference | awk '{print $1}')
picked=$(git log --grep "#END-PICKED#" --format=reference | awk '{print $1}')
echo "BASE=$base,PICKED=$picked"
[ -n "$base" ] && [ -n "$picked" ] || { echo "no #PATCH-BASE#/#END-PICKED# commit in work; run applyPatches.sh first"; exit 1; }

rm -rf ../patches/picked/*
git format-patch --full-index --no-signature --zero-commit -N --ignore-blank-lines -o ../patches/picked "$base...$picked^"
rm -rf ../patches/client/*
git format-patch --full-index --no-signature --zero-commit -N --ignore-blank-lines -o ../patches/client "$picked..."
cd .. && git add patches