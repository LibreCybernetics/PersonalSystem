set -o errexit
set -o nounset
set -o pipefail

usage() {
  cat <<'EOF'
Usage:
  noesis-agent-session create NAME [BASE]
  noesis-agent-session list
  noesis-agent-session path NAME
  noesis-agent-session status NAME
  noesis-agent-session diff NAME
  noesis-agent-session export NAME
  noesis-agent-session destroy NAME

Sessions default to $XDG_STATE_HOME/noesis-agents, or
$HOME/.local/state/noesis-agents when XDG_STATE_HOME is unset.
EOF
}

die() {
  echo "noesis-agent-session: $*" >&2
  exit 1
}

sessions_root() {
  if [[ -n ${NOESIS_AGENT_STATE_DIR:-} ]]; then
    printf '%s\n' "$NOESIS_AGENT_STATE_DIR"
  elif [[ -n ${XDG_STATE_HOME:-} ]]; then
    printf '%s\n' "$XDG_STATE_HOME/noesis-agents"
  else
    printf '%s\n' "$HOME/.local/state/noesis-agents"
  fi
}

validate_name() {
  local name=$1
  [[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
    die "invalid session name '$name' (use letters, digits, dot, underscore, or dash)"
}

session_path() {
  local name=$1
  validate_name "$name"
  printf '%s/%s\n' "$(sessions_root)" "$name"
}

require_session() {
  local session
  session=$(session_path "$1")
  [[ -d $session && ! -L $session ]] || die "session '$1' does not exist"
  [[ -d $session/work && ! -L $session/work ]] || die "session '$1' has no safe worktree"
  [[ -d $session/repo.git && ! -L $session/repo.git ]] ||
    die "session '$1' has no safe repository"
  [[ -f $session/repo.git/config && ! -L $session/repo.git/config ]] ||
    die "session '$1' has no safe repository configuration"
  [[ -d $session/repo.git/worktrees/work && ! -L $session/repo.git/worktrees/work ]] ||
    die "session '$1' has no safe worktree metadata"
  [[ -z $(find "$session/repo.git" -type l -print -quit) ]] ||
    die "session '$1' has symlinks in its Git metadata"
  [[ -f $session/base-commit && ! -L $session/base-commit ]] ||
    die "session '$1' has no recorded base commit"
  printf '%s\n' "$session"
}

session_git() {
  local session=$1
  shift
  GIT_CONFIG_GLOBAL=/dev/null \
    GIT_CONFIG_SYSTEM=/dev/null \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_COMMON_DIR="$session/repo.git" \
    GIT_OPTIONAL_LOCKS=0 \
    GIT_PAGER=cat \
    git \
      --git-dir="$session/repo.git/worktrees/work" \
      --work-tree="$session/work" \
      -c core.fsmonitor=false \
      -c core.hooksPath=/dev/null \
      "$@"
}

create_session() {
  local name=$1
  local base=${2:-HEAD}
  local root session source_root source_commit tree snapshot_commit

  validate_name "$name"
  root=$(sessions_root)
  session="$root/$name"
  mkdir -p -- "$root"
  chmod 700 -- "$root"
  [[ ! -e $session && ! -L $session ]] || die "session '$name' already exists"

  source_root=$(git rev-parse --show-toplevel 2>/dev/null) ||
    die "create must be run inside the source Git repository"
  source_commit=$(git -C "$source_root" rev-parse --verify "$base^{commit}") ||
    die "cannot resolve base '$base'"

  mkdir -- "$session"
  chmod 700 -- "$session"
  trap 'rm -rf -- "$session"' ERR INT TERM

  mkdir -- "$session/snapshot"
  git -C "$source_root" archive --format=tar "$source_commit" |
    tar -xf - -C "$session/snapshot"

  git init --quiet --bare "$session/repo.git"
  git --git-dir="$session/repo.git" config user.name "Noesis agent ($name)"
  git --git-dir="$session/repo.git" config user.email "agent@noesis.invalid"
  git --git-dir="$session/repo.git" config worktree.useRelativePaths true

  git --git-dir="$session/repo.git" --work-tree="$session/snapshot" add -A
  tree=$(git --git-dir="$session/repo.git" --work-tree="$session/snapshot" write-tree)
  snapshot_commit=$(
    printf 'Snapshot of %s at %s\n' "$source_commit" "$base" |
      GIT_AUTHOR_NAME="Noesis session" \
        GIT_AUTHOR_EMAIL="agent@noesis.invalid" \
        GIT_COMMITTER_NAME="Noesis session" \
        GIT_COMMITTER_EMAIL="agent@noesis.invalid" \
        git --git-dir="$session/repo.git" commit-tree "$tree"
  )
  git --git-dir="$session/repo.git" update-ref refs/heads/base "$snapshot_commit"
  git --git-dir="$session/repo.git" worktree add --quiet --relative-paths \
    -b "agent/$name" "$session/work" refs/heads/base

  rm -rf -- "$session/snapshot"
  for provider in codex claude shell; do
    mkdir -p -- \
      "$session/state/$provider/agent" \
      "$session/state/$provider/cache/coursier" \
      "$session/state/$provider/cache/ivy" \
      "$session/state/$provider/cache/sbt" \
      "$session/state/$provider/config" \
      "$session/state/$provider/data" \
      "$session/state/$provider/home" \
      "$session/state/$provider/local-state"
  done
  mkdir -- "$session/proxy"
  chmod -R u+rwX,go-rwx -- "$session/state" "$session/proxy"

  printf '%s\n' "$source_commit" >"$session/source-commit"
  printf '%s\n' "$base" >"$session/source-ref"
  printf '%s\n' "$snapshot_commit" >"$session/base-commit"
  chmod 600 -- "$session/source-commit" "$session/source-ref" "$session/base-commit"
  trap - ERR INT TERM

  echo "Created agent session '$name'"
  echo "  source:   $source_commit ($base)"
  echo "  worktree: $session/work"
  echo "  note:     uncommitted and untracked source files were not copied"
}

list_sessions() {
  local root
  root=$(sessions_root)
  [[ -d $root ]] || exit 0
  find "$root" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | LC_ALL=C sort
}

status_session() {
  local session
  session=$(require_session "$1")
  echo "Source commit: $(<"$session/source-commit")"
  session_git "$session" status --short --branch --ignore-submodules=all
}

diff_session() {
  local session base untracked
  session=$(require_session "$1")
  base=$(<"$session/base-commit")
  session_git "$session" status --short --branch --ignore-submodules=all >&2
  untracked=$(session_git "$session" ls-files --others --exclude-standard)
  if [[ -n $untracked ]]; then
    echo "Warning: untracked files are omitted; stage or commit them before export:" >&2
    printf '%s\n' "$untracked" >&2
  fi
  session_git "$session" diff \
    --binary \
    --no-ext-diff \
    --no-textconv \
    --ignore-submodules=all \
    "$base"
}

destroy_session() {
  local name=$1
  local session answer
  session=$(require_session "$name")

  if [[ ${NOESIS_AGENT_FORCE:-0} != 1 ]]; then
    printf "Destroy session '%s' and all of its state? [y/N] " "$name" >&2
    read -r answer
    [[ $answer == y || $answer == Y ]] || die "not destroyed"
  fi

  rm -rf -- "$session"
  echo "Destroyed agent session '$name'; its worktree and sandbox credentials are gone."
}

command=${1:-}
case "$command" in
  create)
    [[ $# -ge 2 && $# -le 3 ]] || {
      usage >&2
      exit 2
    }
    create_session "$2" "${3:-HEAD}"
    ;;
  list)
    [[ $# -eq 1 ]] || die "list takes no arguments"
    list_sessions
    ;;
  path)
    [[ $# -eq 2 ]] || die "path requires NAME"
    require_session "$2"
    ;;
  status)
    [[ $# -eq 2 ]] || die "status requires NAME"
    status_session "$2"
    ;;
  diff | export)
    [[ $# -eq 2 ]] || die "$command requires NAME"
    diff_session "$2"
    ;;
  destroy)
    [[ $# -eq 2 ]] || die "destroy requires NAME"
    destroy_session "$2"
    ;;
  -h | --help | help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
