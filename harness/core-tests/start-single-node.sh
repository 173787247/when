#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runtime_dir="$repo_root/.loop"
runtime_env="$runtime_dir/runtime.env"
node_env="$runtime_dir/single-node.env"

[ -f "$runtime_env" ] || { echo "local dependencies are not started" >&2; exit 1; }
# shellcheck disable=SC1090
source "$runtime_env"

if [ -f "$node_env" ]; then
  # shellcheck disable=SC1090
  source "$node_env"
  if kill -0 "${LOOP_SINGLE_NODE_PID:-}" 2>/dev/null; then
    echo "single-node server is already running" >&2
    exit 1
  fi
fi

free_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }
run_dir="${WHEN_SINGLE_NODE_RUN_DIR:-$(mktemp -d "$runtime_dir/core-flow.XXXXXX")}"
node_id="${WHEN_SINGLE_NODE_ID:-e2e-node}"
worker_id="${WHEN_SINGLE_NODE_WORKER_ID:-777}"
grpc_port="${WHEN_SINGLE_NODE_GRPC_PORT:-$(free_port)}"
pid_file="$run_dir/server.pid"
server_log="$run_dir/server.log"
app_jar="$repo_root/when-app/target/when-app-1.0.0-SNAPSHOT-runner.jar"
client_jar="$repo_root/when-e2e-test/target/when-e2e-test-1.0.0-SNAPSHOT-runner.jar"

mkdir -p "$run_dir"
if [ ! -f "$app_jar" ] || [ ! -f "$client_jar" ]; then
  ./mvnw -q -pl when-app,when-e2e-test -am package
fi
[ -f "$app_jar" ] || { echo "missing app runner jar" >&2; exit 1; }
[ -f "$client_jar" ] || { echo "missing e2e client runner jar" >&2; exit 1; }

export WHEN_NODE_ID="$node_id"
export WHEN_WORKER_ID="$worker_id"
export WHEN_GRPC_PORT="$grpc_port"
export WHEN_TIMEWHEEL_COUNT=1
export WHEN_NODE_HOST=127.0.0.1

perl -MPOSIX=setsid -e '
  my ($pid_file, $log_file, @command) = @ARGV;
  my $first = fork();
  die "first fork failed: $!\n" unless defined $first;
  exit 0 if $first;
  setsid() or die "setsid failed: $!\n";
  my $second = fork();
  die "second fork failed: $!\n" unless defined $second;
  exit 0 if $second;
  open STDIN, q{<}, q{/dev/null} or die "stdin: $!\n";
  open STDOUT, q{>>}, $log_file or die "stdout: $!\n";
  open STDERR, q{>&}, \*STDOUT or die "stderr: $!\n";
  open my $pid, q{>}, $pid_file or die "pid file: $!\n";
  print {$pid} "$$\n";
  close $pid;
  exec {$command[0]} @command;
  die "exec failed: $!\n";
' "$pid_file" "$server_log" java -jar "$app_jar"

for _ in 1 2 3 4 5 6 7 8 9 10; do [ -s "$pid_file" ] && break; sleep 0.1; done
[ -s "$pid_file" ] || { cat "$server_log" >&2; exit 1; }
server_pid="$(tr -d '[:space:]' < "$pid_file")"

tmp_env="$node_env.tmp"
{
  printf 'export LOOP_SINGLE_NODE_PID=%q\n' "$server_pid"
  printf 'export LOOP_CORE_FLOW_DIR=%q\n' "$run_dir"
  printf 'export WHEN_E2E_SERVER_HOST=127.0.0.1\n'
  printf 'export WHEN_E2E_SERVER_PORT=%q\n' "$grpc_port"
  printf 'export WHEN_E2E_NODE_ID=%q\n' "$node_id"
  printf 'export WHEN_E2E_WORKER_ID=%q\n' "$worker_id"
  printf 'export WHEN_E2E_SERVER_LOG=%q\n' "$server_log"
  printf 'export WHEN_E2E_CLIENT_JAR=%q\n' "$client_jar"
} > "$tmp_env"
mv "$tmp_env" "$node_env"
printf 'single-node server started: node=%s port=%s pid=%s log=%s\n' "$node_id" "$grpc_port" "$server_pid" "$server_log"
