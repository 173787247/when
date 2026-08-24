#!/usr/bin/env python3
"""Codex-specific adapter. The Runner, not this adapter, owns branches and judges."""
from __future__ import annotations
import json, os, pathlib, shutil, subprocess, sys

MAX_CAPTURE = 10 * 1024 * 1024

def redact(text: str) -> str:
    import re
    text = re.sub(r'(?i)(password|token|secret|authorization|cookie)\s*[=:]\s*[^\s,]+', r'\1=[REDACTED]', text)
    text = re.sub(r'redis://[^\s]+', 'redis://[REDACTED]', text)
    return text

def probe() -> int:
    if not shutil.which('codex'):
        print('codex executable not found', file=sys.stderr); return 1
    print('adapter ready: codex exec --sandbox workspace-write --json -')
    return 0

def invoke(request: dict) -> dict:
    prompt = pathlib.Path(request['prompt_file']).read_text(encoding='utf-8')
    env = {key: os.environ[key] for key in request.get('environment_allowlist', ['PATH']) if key in os.environ}
    command = ['codex', 'exec', '--sandbox', 'workspace-write', '--json', '-']
    try:
        completed = subprocess.run(command, input=prompt, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   cwd=request['workspace'], env=env, timeout=request.get('timeout_seconds', 1800))
    except subprocess.TimeoutExpired as exc:
        return {'status':'retryable_failed','summary':f'Codex timed out after {request.get("timeout_seconds")}s', 'raw_output':redact((exc.stdout or '')[-MAX_CAPTURE:]), 'needs_confirmation':False}
    output = redact((completed.stdout + '\n' + completed.stderr)[-MAX_CAPTURE:])
    summary = 'Codex completed' if completed.returncode == 0 else f'Codex exited {completed.returncode}'
    for line in reversed(completed.stdout.splitlines()):
        try:
            event=json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict) and isinstance(event.get('summary'), str):
            summary=event['summary']; break
    return {'status':'completed' if completed.returncode == 0 else 'failed', 'summary':redact(summary), 'reported_changed_files':[], 'decisions':[], 'risks':[], 'needs_confirmation':False, 'raw_output':output}

def main() -> int:
    if '--probe' in sys.argv: return probe()
    try:
        request=json.load(sys.stdin)
        result=invoke(request)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except Exception as exc:
        print(json.dumps({'status':'failed','summary':redact(str(exc)),'needs_confirmation':False}), flush=True)
        return 1
if __name__ == '__main__': raise SystemExit(main())
