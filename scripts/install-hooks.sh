#!/bin/sh
# GURU by Unus Lumen — an AI framework that gives a language model real hands on an Android phone.
# Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
# Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
#
# Install the privacy gate into every checkout on this machine.
#
# WHY A HOOK AND NOT A DOCUMENTED STEP. A step a person has to remember is a step that does not
# run, and this project has the evidence: the gate was written into the dev loop as required,
# the script it named was never installed, and four separate leaks shipped. Git runs a
# pre-commit hook itself, on every commit, whether anyone remembered or not.
#
# WHY THIS SCRIPT EXISTS SEPARATELY FROM THE HOOK. `.git/hooks` is not version controlled, so
# the hook cannot be committed there. The hook body lives at `scripts/hooks/pre-commit` and
# this script copies it in. A worktree has its own hooks directory inside the main repository's
# `.git/worktrees/<name>/hooks`, and this script finds whichever one applies.
#
# Usage: scripts/install-hooks.sh [--all-worktrees]

set -eu

root=$(git rev-parse --show-toplevel)

# The hooks directory for THIS checkout. `git rev-parse --git-path hooks` resolves correctly
# for a normal checkout, for a worktree, and for a bare repository, which is why it is used
# rather than a guess at `.git/hooks`.
hooks_dir=$(cd "$root" && git rev-parse --git-path hooks)
case "$hooks_dir" in
  /*) ;;
  *) hooks_dir="$root/$hooks_dir" ;;
esac

mkdir -p "$hooks_dir"
install -m 0755 "$root/scripts/hooks/pre-commit" "$hooks_dir/pre-commit"
printf 'privacy gate: installed %s\n' "$hooks_dir/pre-commit"

if [ "${1:-}" = "--all-worktrees" ]; then
  # Every worktree this repository knows about, including ones with their own hooks dir.
  git worktree list --porcelain | awk '/^worktree /{print substr($0, 10)}' | while read -r wt; do
    [ -d "$wt" ] || continue
    wt_hooks=$(cd "$wt" && git rev-parse --git-path hooks)
    case "$wt_hooks" in
      /*) ;;
      *) wt_hooks="$wt/$wt_hooks" ;;
    esac
    mkdir -p "$wt_hooks"
    install -m 0755 "$root/scripts/hooks/pre-commit" "$wt_hooks/pre-commit"
    printf 'privacy gate: installed %s\n' "$wt_hooks/pre-commit"
  done
fi
