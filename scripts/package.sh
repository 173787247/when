#!/usr/bin/env bash
# Package the current branch's complete non-ignored working tree and, when explicitly
# requested, synchronize that snapshot to the same branch in a remote Git checkout.
#
# Usage:
#   ./scripts/package.sh [--execute] [output_dir]
#
# The remote synchronization target is fixed for this repository:
#   root@117.72.92.117:/root/when
#
# The archive contains tracked files plus untracked files that are not ignored. It excludes
# .gitignore matches even when a path is already tracked, .git/, and tar.gz archives. The
# remote operation never runs unless --execute is supplied.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
EXECUTE=0
OUTPUT_DIR="$PROJECT_ROOT"
REMOTE_HOST="root@117.72.92.117"
REMOTE_DIR="/root/when"

info() { printf '[INFO]  %s\n' "$*"; }
warn() { printf '[WARN]  %s\n' "$*" >&2; }
error() { printf '[ERROR] %s\n' "$*" >&2; }

usage() {
  cat <<'USAGE'
Usage: ./scripts/package.sh [--execute] [output_dir]

Without --execute, create and keep a local tar.gz only.
With --execute, also commit the packageable local paths, upload the archive, extract it
into the same-name branch at root@117.72.92.117:/root/when, commit there, and push it.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --execute)
      EXECUTE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --*)
      error "unknown option: $1"
      usage >&2
      exit 2
      ;;
    *)
      if [[ "$OUTPUT_DIR" != "$PROJECT_ROOT" ]]; then
        error "only one output directory may be supplied"
        usage >&2
        exit 2
      fi
      OUTPUT_DIR="$1"
      shift
      ;;
  esac
done

git -C "$PROJECT_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
  || { error "project root is not a Git work tree: $PROJECT_ROOT"; exit 1; }

LOCAL_BRANCH="$(git -C "$PROJECT_ROOT" symbolic-ref --quiet --short HEAD || true)"
[[ -n "$LOCAL_BRANCH" ]] || { error "detached HEAD cannot be synchronized to a branch"; exit 1; }
git -C "$PROJECT_ROOT" check-ref-format --branch "$LOCAL_BRANCH" >/dev/null \
  || { error "invalid current branch name: $LOCAL_BRANCH"; exit 1; }

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"

VERSION="$(git -C "$PROJECT_ROOT" describe --tags --always --dirty 2>/dev/null || printf 'dev')"
SAFE_BRANCH="${LOCAL_BRANCH//\//-}"
SAFE_VERSION="$(printf '%s' "$VERSION" | tr '/[:space:]' '--')"
TIMESTAMP="$(date +%Y%m%d%H%M%S)_$$"
ARCHIVE="$OUTPUT_DIR/when-${SAFE_BRANCH}-${SAFE_VERSION}-${TIMESTAMP}.tar.gz"
ARCHIVE_NAME="$(basename "$ARCHIVE")"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/when-package.XXXXXX")"
MANIFEST="$WORK_DIR/files.nul"
DELETED_MANIFEST="$WORK_DIR/deleted.nul"
PATHS_TO_COMMIT="$WORK_DIR/paths-to-commit.nul"

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

is_gitignored() {
  # --no-index makes the check honor .gitignore rules for tracked files too.
  git -C "$PROJECT_ROOT" check-ignore --no-index -q -- "$1"
}

append_if_packageable() {
  local path="$1"
  [[ -n "$path" ]] || return 0
  [[ "$path" != .git/* ]] || return 0
  [[ "$path" != *.tar.gz ]] || return 0
  is_gitignored "$path" && return 0
  printf '%s\0' "$path" >> "$MANIFEST"
}

while IFS= read -r -d '' path; do
  append_if_packageable "$path"
done < <(git -C "$PROJECT_ROOT" ls-files --cached --others --exclude-standard -z)

[[ -s "$MANIFEST" ]] || { error "no packageable files were found"; exit 1; }

while IFS= read -r -d '' path; do
  [[ -n "$path" && ! -e "$PROJECT_ROOT/$path" ]] || continue
  [[ "$path" != .git/* ]] || continue
  is_gitignored "$path" && continue
  printf '%s\0' "$path" >> "$DELETED_MANIFEST"
done < <(
  if git -C "$PROJECT_ROOT" rev-parse --verify --quiet "refs/remotes/origin/$LOCAL_BRANCH" >/dev/null; then
    git -C "$PROJECT_ROOT" diff --name-only -z --diff-filter=D "origin/$LOCAL_BRANCH" HEAD || true
  fi
  git -C "$PROJECT_ROOT" diff --cached --name-only -z --diff-filter=D || true
  git -C "$PROJECT_ROOT" diff --name-only -z --diff-filter=D || true
)

file_count="$(tr -cd '\0' < "$MANIFEST" | wc -c | tr -d ' ')"
deleted_count=0
if [[ -s "$DELETED_MANIFEST" ]]; then
  deleted_count="$(tr -cd '\0' < "$DELETED_MANIFEST" | wc -c | tr -d ' ')"
fi

info "Current branch: $LOCAL_BRANCH"
info "Packageable files: $file_count"
info "Deleted paths to mirror: $deleted_count"
info "Creating archive: $ARCHIVE"
COPYFILE_DISABLE=1 tar -czf "$ARCHIVE" -C "$PROJECT_ROOT" --null -T "$MANIFEST"
info "Archive created ($(du -h "$ARCHIVE" | awk '{print $1}'))"

if [[ "$EXECUTE" -eq 0 ]]; then
  info "Local package complete. Use --execute to synchronize it to a remote checkout."
  exit 0
fi

cat "$MANIFEST" "$DELETED_MANIFEST" > "$PATHS_TO_COMMIT"
info "Staging packageable local paths"
git -C "$PROJECT_ROOT" add -A \
  --pathspec-from-file="$PATHS_TO_COMMIT" \
  --pathspec-file-nul

COMMIT_MESSAGE="chore: sync ${LOCAL_BRANCH} snapshot (${file_count} file(s), ${deleted_count} deletion(s))"
if git -C "$PROJECT_ROOT" diff --cached --quiet; then
  info "No packageable local changes require a commit."
else
  git -C "$PROJECT_ROOT" commit -m "$COMMIT_MESSAGE"
  info "Local snapshot commit created."
fi

DELETED_B64=""
if [[ -s "$DELETED_MANIFEST" ]]; then
  DELETED_B64="$(base64 < "$DELETED_MANIFEST" | tr -d '\n')"
fi
COMMIT_MESSAGE_B64="$(printf '%s' "$COMMIT_MESSAGE" | base64 | tr -d '\n')"

info "Uploading $ARCHIVE_NAME to $REMOTE_HOST:$REMOTE_DIR"
scp "$ARCHIVE" "$REMOTE_HOST:$REMOTE_DIR/$ARCHIVE_NAME"

info "Synchronizing remote branch $LOCAL_BRANCH"
ssh "$REMOTE_HOST" \
  REMOTE_DIR="$REMOTE_DIR" \
  LOCAL_BRANCH="$LOCAL_BRANCH" \
  ARCHIVE_NAME="$ARCHIVE_NAME" \
  DELETED_B64="$DELETED_B64" \
  COMMIT_MESSAGE_B64="$COMMIT_MESSAGE_B64" \
  'bash -s' <<'REMOTE_SCRIPT'
set -euo pipefail

info() { printf '[INFO]  %s\n' "$*"; }
error() { printf '[ERROR] %s\n' "$*" >&2; }

[[ "$REMOTE_DIR" = /* ]] || { error "remote directory must be absolute"; exit 1; }
cd "$REMOTE_DIR"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
  || { error "remote directory is not a Git work tree"; exit 1; }
git check-ref-format --branch "$LOCAL_BRANCH" >/dev/null \
  || { error "invalid branch name"; exit 1; }

remote_dirty="$(git status --porcelain --untracked-files=all | awk -v archive="$ARCHIVE_NAME" '$0 != "?? " archive')"
if [[ -n "$remote_dirty" ]]; then
  error "remote working tree is not clean; refusing to overwrite it"
  exit 1
fi

git fetch origin
if git show-ref --verify --quiet "refs/heads/$LOCAL_BRANCH"; then
  git checkout "$LOCAL_BRANCH"
elif git show-ref --verify --quiet "refs/remotes/origin/$LOCAL_BRANCH"; then
  git checkout -b "$LOCAL_BRANCH" "origin/$LOCAL_BRANCH"
else
  git checkout -b "$LOCAL_BRANCH"
fi

if git show-ref --verify --quiet "refs/remotes/origin/$LOCAL_BRANCH"; then
  git pull --ff-only origin "$LOCAL_BRANCH"
fi

[[ -f "$ARCHIVE_NAME" ]] || { error "uploaded archive is missing: $ARCHIVE_NAME"; exit 1; }
tar -xzf "$ARCHIVE_NAME"
rm -f "$ARCHIVE_NAME"

deleted_file="$(mktemp)"
trap 'rm -f "$deleted_file"' EXIT
if [[ -n "${DELETED_B64:-}" ]]; then
  printf '%s' "$DELETED_B64" | base64 -d > "$deleted_file"
  while IFS= read -r -d '' path; do
    case "$path" in
      ''|/*|..|../*|*/../*)
        error "refusing unsafe deleted path"
        exit 1
        ;;
    esac
    rm -f -- "$REMOTE_DIR/$path"
  done < "$deleted_file"
fi

git add -A
if git diff --cached --quiet; then
  info "Remote tree already matches the archive."
else
  commit_message="$(printf '%s' "$COMMIT_MESSAGE_B64" | base64 -d)"
  [[ -n "$commit_message" ]] || commit_message='chore: sync branch snapshot'
  git commit -m "$commit_message"
  git push origin "$LOCAL_BRANCH"
  info "Remote branch pushed: $LOCAL_BRANCH"
fi
REMOTE_SCRIPT

info "Remote sync complete. Archive retained locally: $ARCHIVE"
if git -C "$PROJECT_ROOT" fetch origin "$LOCAL_BRANCH" --quiet; then
  if git -C "$PROJECT_ROOT" diff --quiet "origin/$LOCAL_BRANCH" HEAD; then
    warn "local and remote trees match but may have different commit IDs; no destructive reset was performed"
  fi
else
  warn "remote push succeeded, but local fetch for convergence failed"
fi
