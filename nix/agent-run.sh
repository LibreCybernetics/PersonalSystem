# shellcheck disable=SC2016
# The embedded single-quoted program is evaluated by the shell inside
# Bubblewrap, where bridge_pid exists; it must not expand in this wrapper.

set -o errexit
set -o nounset
set -o pipefail

die() {
  echo "noesis-agent-run: $*" >&2
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
  [[ $1 =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
    die "invalid session name '$1'"
}

[[ $# -ge 2 ]] || die "usage: noesis-agent-run PROVIDER SESSION [AGENT_ARGS...]"
provider=$1
name=$2
shift 2

case "$provider" in
  codex | claude | shell) ;;
  *) die "provider must be 'codex', 'claude', or 'shell'" ;;
esac

validate_name "$name"
session="$(sessions_root)/$name"
[[ -d $session && ! -L $session ]] || die "session '$name' does not exist"
for component in repo.git work state proxy; do
  [[ -d $session/$component && ! -L $session/$component ]] ||
    die "unsafe or missing session component '$component'"
done
[[ -f $session/repo.git/config && ! -L $session/repo.git/config ]] ||
  die "unsafe or missing repository configuration"
provider_state="$session/state/$provider"
[[ -d $provider_state && ! -L $provider_state ]] ||
  die "unsafe or missing state for provider '$provider'"

session=$(realpath -e -- "$session")
provider_state=$(realpath -e -- "$provider_state")
proxy_socket="$session/proxy/connect.sock"
rm -f -- "$proxy_socket"

default_domains=(
  api.openai.com
  auth.openai.com
  openai.com
  chatgpt.com
  oaistatic.com
  oaiusercontent.com
  api.anthropic.com
  anthropic.com
  claude.ai
  claude.com
  repo1.maven.org
  repo.maven.apache.org
  repo.scala-sbt.org
)

proxy_args=(--socket "$proxy_socket")
if [[ ${NOESIS_AGENT_NO_DEFAULT_DOMAINS:-0} != 1 ]]; then
  for domain in "${default_domains[@]}"; do
    proxy_args+=(--allow "$domain")
  done
fi
if [[ -n ${NOESIS_AGENT_EXTRA_DOMAINS:-} ]]; then
  IFS=',' read -r -a extra_domains <<<"$NOESIS_AGENT_EXTRA_DOMAINS"
  for domain in "${extra_domains[@]}"; do
    [[ -n $domain ]] && proxy_args+=(--allow "$domain")
  done
fi
[[ ${#proxy_args[@]} -gt 2 ]] || die "network policy has no allowed domains"

"$NOESIS_AGENT_PROXY/bin/noesis-agent-proxy" "${proxy_args[@]}" &
proxy_pid=$!
cleanup() {
  kill "$proxy_pid" 2>/dev/null || true
  wait "$proxy_pid" 2>/dev/null || true
  rm -f -- "$proxy_socket"
}
trap cleanup EXIT INT TERM

for _ in {1..100}; do
  [[ -S $proxy_socket ]] && break
  kill -0 "$proxy_pid" 2>/dev/null || die "network proxy exited during startup"
  sleep 0.05
done
[[ -S $proxy_socket ]] || die "network proxy did not create its socket"

bwrap_args=(
  --unshare-user
  --unshare-ipc
  --unshare-pid
  --unshare-net
  --unshare-uts
  --unshare-cgroup-try
  --disable-userns
  --die-with-parent
  --new-session
  --hostname noesis-agent
  --cap-drop ALL
  --clearenv
  --dir /bin
  --dir /etc
  --dir /home
  --dir /nix
  --dir /nix/store
  --dir /run
  --dir /run/agent-proxy
  --dir /session
  --dir /state
  --dir /tmp
  --proc /proc
  --dev /dev
  --tmpfs /tmp
  --bind "$session/repo.git" /session/repo.git
  --ro-bind "$session/repo.git/config" /session/repo.git/config
  --bind "$session/work" /session/work
  --bind "$provider_state" /state
  --ro-bind "$session/proxy" /run/agent-proxy
  --symlink "$NOESIS_AGENT_ENV/bin/bash" /bin/bash
  --symlink "$NOESIS_AGENT_ENV/bin/bash" /bin/sh
  --chdir /session/work
  --setenv HOME /state/home
  --setenv XDG_CONFIG_HOME /state/config
  --setenv XDG_CACHE_HOME /state/cache
  --setenv XDG_DATA_HOME /state/data
  --setenv XDG_STATE_HOME /state/local-state
  --setenv TMPDIR /tmp
  --setenv PATH "$NOESIS_AGENT_ENV/bin"
  --setenv JAVA_HOME "$NOESIS_AGENT_JAVA"
  --setenv COURSIER_CACHE /state/cache/coursier
  --setenv IVY_HOME /state/cache/ivy
  --setenv SBT_OPTS "-Dsbt.global.base=/state/cache/sbt -Dsbt.ivy.home=/state/cache/ivy -Dsbt.boot.directory=/state/cache/sbt/boot"
  --setenv SSL_CERT_FILE "$NOESIS_AGENT_CA_BUNDLE"
  --setenv CODEX_CA_CERTIFICATE "$NOESIS_AGENT_CA_BUNDLE"
  --setenv LC_ALL C.UTF-8
  --setenv LANG C.UTF-8
  --setenv NOESIS_AGENT_SANDBOX 1
  --setenv HTTP_PROXY http://127.0.0.1:3128
  --setenv HTTPS_PROXY http://127.0.0.1:3128
  --setenv ALL_PROXY http://127.0.0.1:3128
  --setenv http_proxy http://127.0.0.1:3128
  --setenv https_proxy http://127.0.0.1:3128
  --setenv all_proxy http://127.0.0.1:3128
  --setenv NO_PROXY ""
  --setenv no_proxy ""
)

while IFS= read -r store_path; do
  [[ -e $store_path ]] || die "missing Nix closure path '$store_path'"
  bwrap_args+=(--ro-bind "$store_path" "$store_path")
done <"$NOESIS_AGENT_CLOSURE"

case "$provider" in
  codex)
    agent_command=(
      "$NOESIS_AGENT_ENV/bin/codex"
      -C /session/work
      -c 'cli_auth_credentials_store="file"'
      -c 'shell_environment_policy.inherit="all"'
      --sandbox danger-full-access
      --ask-for-approval on-request
      "$@"
    )
    bwrap_args+=(--setenv CODEX_HOME /state/agent)
    ;;
  claude)
    agent_command=(
      "$NOESIS_AGENT_ENV/bin/claude"
      --permission-mode acceptEdits
      --no-chrome
      --strict-mcp-config
      --mcp-config '{"mcpServers":{}}'
      "$@"
    )
    bwrap_args+=(
      --setenv CLAUDE_CONFIG_DIR /state/agent
      --setenv CLAUDE_CODE_PROXY_RESOLVES_HOSTS 1
      --setenv CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC 1
    )
    ;;
  shell)
    if [[ $# -eq 0 ]]; then
      agent_command=("$NOESIS_AGENT_ENV/bin/bash")
    else
      agent_command=("$@")
    fi
    ;;
esac

sandbox_command=(
  "$NOESIS_AGENT_ENV/bin/bash"
  -c
  '
    set -o errexit
    set -o nounset
    set -o pipefail
    ulimit -c 0
    socat TCP4-LISTEN:3128,bind=127.0.0.1,reuseaddr,fork UNIX-CONNECT:/run/agent-proxy/connect.sock &
    bridge_pid=$!
    trap '"'"'kill "$bridge_pid" 2>/dev/null || true; wait "$bridge_pid" 2>/dev/null || true'"'"' EXIT INT TERM
    "$@"
  '
  agent-sandbox
  "${agent_command[@]}"
)

runner=()
if [[ ${NOESIS_AGENT_SYSTEMD_SCOPE:-auto} != 0 ]] &&
  command -v systemd-run >/dev/null 2>&1 &&
  systemctl --user show-environment >/dev/null 2>&1; then
  runner=(
    systemd-run
    --user
    --scope
    --quiet
    --collect
    --property=MemoryHigh="${NOESIS_AGENT_MEMORY_HIGH:-6G}"
    --property=MemoryMax="${NOESIS_AGENT_MEMORY_MAX:-8G}"
    --property=TasksMax="${NOESIS_AGENT_TASKS_MAX:-1024}"
    --property=CPUQuota="${NOESIS_AGENT_CPU_QUOTA:-400%}"
  )
elif [[ ${NOESIS_AGENT_SYSTEMD_SCOPE:-auto} == 1 ]]; then
  die "NOESIS_AGENT_SYSTEMD_SCOPE=1 but the user systemd manager is unavailable"
fi

"${runner[@]}" "$NOESIS_AGENT_BWRAP/bin/bwrap" "${bwrap_args[@]}" -- "${sandbox_command[@]}"
