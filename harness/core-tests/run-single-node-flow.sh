#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runtime_env="$repo_root/.loop/runtime.env"
node_env="$repo_root/.loop/single-node.env"
[ -f "$runtime_env" ] && [ -f "$node_env" ] || { echo "dependencies or single-node server are not started" >&2; exit 1; }
# shellcheck disable=SC1090
source "$runtime_env"
# shellcheck disable=SC1090
source "$node_env"

results_file="$LOOP_CORE_FLOW_DIR/e2e-results.log"
: > "$results_file"
client() { java -jar "$WHEN_E2E_CLIENT_JAR" "$@"; }
record() { printf '%s\n' "$*" | tee -a "$results_file"; }
require_status() {
  local action="$1" expected="$2" message_id="${3:-}" output
  if [ -n "$message_id" ]; then output="$(client "$action" "$message_id")"; else output="$(client "$action")"; fi
  printf '%s\n' "$output" >> "$results_file"
  grep -qx "status=$expected" <<< "$output" || { record "FAIL action=$action expected=$expected"; exit 1; }
}
submit() {
  local delay="$1" output id
  output="$(client submit "$delay")"
  printf '%s\n' "$output" >> "$results_file"
  grep -qx 'status=PENDING' <<< "$output" || { record "FAIL submit_status"; exit 1; }
  id="$(awk -F= '$1 == "message_id" {print $2; exit}' <<< "$output")"
  [ -n "$id" ] || { record "FAIL missing_message_id"; exit 1; }
  printf '%s\n' "$id"
}
redis_has_status() {
  local message_id="$1" expected="$2" value
  value="$(redis-cli -h "$WHEN_REDIS_HOST" -p "$WHEN_REDIS_PORT" --raw GET "when:msg:$message_id")"
  grep -Fq "\"status\":\"$expected\"" <<< "$value" || { record "FAIL redis_status message_id=$message_id expected=$expected"; exit 1; }
  record "redis_status=$expected message_id=$message_id"
}
wait_for_log() {
  local message_id="$1" expected_count="$2" attempts=32 lines count
  while [ "$attempts" -gt 0 ]; do
    lines="$(grep -F -- "$message_id" "$WHEN_E2E_SERVER_LOG" 2>/dev/null | grep -F 'event=when_due' || true)"
    count="$(printf '%s\n' "$lines" | sed '/^$/d' | wc -l | tr -d ' ')"
    if [ "$count" = "$expected_count" ]; then
      [ "$expected_count" = 0 ] || printf '%s\n' "$lines" >> "$results_file"
      return 0
    fi
    sleep 0.25
    attempts=$((attempts - 1))
  done
  record "FAIL due_log message_id=$message_id expected_count=$expected_count"
  exit 1
}
wait_for_ready() {
  local attempts=120 output
  while [ "$attempts" -gt 0 ]; do
    if output="$(client health 2>/dev/null)" && grep -qx 'health=SERVING' <<< "$output"; then
      printf '%s\n' "$output" >> "$results_file"
      etcdctl --endpoints="$WHEN_ETCD_ENDPOINTS" get "/when/nodes/$WHEN_E2E_NODE_ID" | grep -Fq "$WHEN_E2E_NODE_ID"
      etcdctl --endpoints="$WHEN_ETCD_ENDPOINTS" get "/when/workers/$WHEN_E2E_WORKER_ID" | grep -Fxq "$WHEN_E2E_NODE_ID"
      record "server_ready node_id=$WHEN_E2E_NODE_ID"
      return 0
    fi
    sleep 0.25
    attempts=$((attempts - 1))
  done
  record "FAIL server_not_ready"
  exit 1
}

wait_for_ready
sleep 2
wait_for_ready

due_message_id="$(submit 2)"
require_status query PENDING "$due_message_id"
redis_has_status "$due_message_id" PENDING
wait_for_log "$due_message_id" 1
require_status query DELIVERING "$due_message_id"
redis_has_status "$due_message_id" DELIVERING
record "due_flow=PASS message_id=$due_message_id"

cancelled_message_id="$(submit 5)"
require_status query PENDING "$cancelled_message_id"
redis_has_status "$cancelled_message_id" PENDING
require_status cancel CANCELLED "$cancelled_message_id"
require_status query CANCELLED "$cancelled_message_id"
redis_has_status "$cancelled_message_id" CANCELLED
sleep 8
wait_for_log "$cancelled_message_id" 0
record "cancel_flow=PASS message_id=$cancelled_message_id"

restart_message_id="$(submit 6)"
require_status query PENDING "$restart_message_id"
redis_has_status "$restart_message_id" PENDING
old_node_id="$WHEN_E2E_NODE_ID"
old_worker_id="$WHEN_E2E_WORKER_ID"
old_port="$WHEN_E2E_SERVER_PORT"
old_run_dir="$LOOP_CORE_FLOW_DIR"
"$repo_root/harness/core-tests/stop-single-node.sh"
WHEN_SINGLE_NODE_ID="$old_node_id" \
WHEN_SINGLE_NODE_WORKER_ID="$old_worker_id" \
WHEN_SINGLE_NODE_GRPC_PORT="$old_port" \
WHEN_SINGLE_NODE_RUN_DIR="$old_run_dir" \
  "$repo_root/harness/core-tests/start-single-node.sh"
# shellcheck disable=SC1090
source "$node_env"
wait_for_ready
wait_for_log "$restart_message_id" 1
require_status query DELIVERING "$restart_message_id"
redis_has_status "$restart_message_id" DELIVERING
record "restart_flow=PASS message_id=$restart_message_id"

printf 'E2E_SINGLE_NODE=PASS\n' >> "$results_file"
record "results_file=$results_file"
