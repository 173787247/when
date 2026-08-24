#!/usr/bin/env python3
"""Stateful, phase-aware, single-repository Runner for the When Loop."""
from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import fnmatch
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOOP_DIR = ROOT / '.loop'
STATE = LOOP_DIR / 'state.json'
BACKUP = LOOP_DIR / 'state.json.bak'
LOCK = LOOP_DIR / 'run.lock'
MAX_LOG = 10 * 1024 * 1024
PHASE_ORDER = ('first', 'second')
PHASE_MESSAGES = {'first': 'FIRST PHASE COMPLETE', 'second': 'SECOND PHASE COMPLETE'}
RELEASE_CANDIDATE_CONTRACT = [
    ('second_full_integration', ['harness/release/run-full-integration.sh'], ['lesson47', 'lesson48', 'lesson49', 'lesson51']),
    ('second_ha_failover', ['harness/release/run-ha-failover.sh'], ['lesson47', 'lesson48']),
    ('second_observability', ['harness/release/verify-observability.sh'], ['lesson50']),
    ('second_release_artifacts', ['harness/release/verify-artifacts.sh'], ['lesson52']),
]
SECOND_FINAL_CONTRACT = [
    ('second_full_regression', ['harness/release/run-full-regression.sh']),
    ('second_documentation_smoke', ['harness/release/verify-docs.sh']),
    ('second_release_report_complete', ['harness/release/verify-release-report.sh']),
]
TOP_KEYS = {
    'version', 'project', 'git', 'runtime', 'agent', 'defaults',
    'reference_docs', 'orchestrator_spec', 'common_contract',
    'protected_paths', 'phases', 'stages', 'final_judges',
    'release_candidate_judges', 'second_final_judges',
}
STAGE_KEYS = {
    'id', 'phase', 'kind', 'lesson', 'branch', 'specs', 'depends_on',
    'services', 'write_paths', 'shared_write_paths', 'fingerprint_paths',
    'stage_protected_path_overrides', 'judge',
}
PHASE_KEYS = {
    'requires_phase', 'stages', 'release_candidate_judges',
    'delivery_stage', 'final_judges', 'completion_message',
}
JUDGE_KEYS = {'id', 'command', 'timeout_seconds', 'always_run', 'owner_stages'}


class LoopError(RuntimeError):
    def __init__(self, message: str, code: int = 3):
        super().__init__(message)
        self.code = code


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def shell(
    cmd: list[str], *, timeout: int = 1200, check: bool = True,
    capture: bool = True, env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess:
    runenv = os.environ.copy()
    runenv.update(env or {})
    result = subprocess.run(
        cmd, cwd=ROOT, text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
        timeout=timeout, env=runenv,
    )
    if check and result.returncode:
        raise LoopError(
            f'command failed ({result.returncode}): {" ".join(cmd)}\n'
            f'{result.stderr[-2000:]}', 4,
        )
    return result


def git(*args: str, **kwargs: Any) -> subprocess.CompletedProcess:
    return shell(['git', *args], **kwargs)


def sha_bytes(data: bytes) -> str:
    return 'sha256:' + hashlib.sha256(data).hexdigest()


def path_hash(patterns: list[str], exclude_patterns: list[str] | None = None) -> str:
    entries = []
    excludes = exclude_patterns or []
    for path in sorted(ROOT.rglob('*')):
        if (
            not path.is_file() or '.git' in path.parts or '.loop' in path.parts
            or 'target' in path.parts or '__pycache__' in path.parts
            or path.suffix in {'.pyc', '.pyo'}
        ):
            continue
        rel = path.relative_to(ROOT).as_posix()
        if (
            any(fnmatch.fnmatchcase(rel, pattern) for pattern in patterns)
            and not any(fnmatch.fnmatchcase(rel, pattern) for pattern in excludes)
        ):
            entries.append(rel.encode() + b'\0' + hashlib.sha256(path.read_bytes()).digest())
    return sha_bytes(b''.join(entries))


def read_json(path: pathlib.Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding='utf-8'))


def runtime_environment(env_file: pathlib.Path) -> dict[str, str]:
    if not env_file.exists():
        return {}
    environment = {}
    for line in env_file.read_text(encoding='utf-8').splitlines():
        if '=' not in line:
            continue
        key, value = line.split('=', 1)
        environment[key] = value.strip("'")
    return environment


def atomic_write(path: pathlib.Path, data: dict[str, Any], preserve_backup: bool = True) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = (json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + '\n').encode()
    fd, temp = tempfile.mkstemp(prefix=f'.{path.name}.', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as handle:
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        if preserve_backup and path.exists():
            shutil.copy2(path, BACKUP)
        os.replace(temp, path)
        directory = os.open(path.parent, os.O_RDONLY)
        os.fsync(directory)
        os.close(directory)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def _validate_judges(judges: list[dict[str, Any]], label: str) -> list[str]:
    ids = []
    for judge in judges:
        unknown = set(judge) - JUDGE_KEYS
        if unknown:
            raise LoopError(f'unknown field in {label} judge {judge.get("id")}: {unknown}')
        if not judge.get('id'):
            raise LoopError(f'{label} judge missing id')
        command = judge.get('command')
        if not isinstance(command, list) or not command or not all(isinstance(x, str) and x for x in command):
            raise LoopError(f'{label} judge {judge["id"]} command must be a non-empty argument array')
        ids.append(judge['id'])
    if len(ids) != len(set(ids)):
        raise LoopError(f'duplicate {label} judge id')
    return ids


def validate_config(data: dict[str, Any]) -> None:
    if not isinstance(data, dict):
        raise LoopError('configuration must be an object')
    unknown = set(data) - TOP_KEYS
    if unknown:
        raise LoopError('unknown configuration fields: ' + ', '.join(sorted(unknown)))
    missing = TOP_KEYS - set(data)
    if missing:
        raise LoopError('missing configuration fields: ' + ', '.join(sorted(missing)))
    if data['version'] != 1:
        raise LoopError('only configuration version 1 is supported')

    if tuple(data['phases']) != PHASE_ORDER:
        raise LoopError('official phases must be first then second')
    for phase_name, phase in data['phases'].items():
        unknown = set(phase) - PHASE_KEYS
        if unknown:
            raise LoopError(f'unknown field in phase {phase_name}: {unknown}')
        if phase.get('completion_message') != PHASE_MESSAGES[phase_name]:
            raise LoopError(f'phase {phase_name} has invalid completion_message')

    stages = data['stages']
    ids = []
    lessons = []
    for stage in stages:
        unknown = set(stage) - STAGE_KEYS
        if unknown:
            raise LoopError(f'unknown field in stage {stage.get("id")}: {unknown}')
        for key in ('id', 'phase', 'lesson', 'branch', 'specs', 'depends_on', 'write_paths', 'fingerprint_paths', 'judge'):
            if key not in stage:
                raise LoopError(f'stage missing {key}: {stage}')
        command = stage['judge'].get('command')
        if not isinstance(command, list) or not command or not all(isinstance(x, str) and x for x in command):
            raise LoopError(f'stage {stage["id"]} judge command must be an argument array')
        if stage['branch'] != f'lesson/{stage["lesson"]}':
            raise LoopError(f'stage {stage["id"]} must use branch lesson/{stage["lesson"]}')
        ids.append(stage['id'])
        lessons.append(stage['lesson'])

    expected_lessons = [39, 40, 41, 42, 43, 44, 45, 47, 48, 49, 50, 51, 52, 53]
    expected_ids = [f'lesson{lesson}' for lesson in expected_lessons]
    if ids != expected_ids or lessons != expected_lessons:
        raise LoopError('official stages must be lesson39-45 followed by lesson47-53 in order')
    if len(ids) != len(set(ids)):
        raise LoopError('stage IDs must be unique')

    phases = data['phases']
    if phases['first'].get('stages') != [f'lesson{x}' for x in range(39, 46)]:
        raise LoopError('first phase stages must be lesson39 through lesson45 in order')
    if phases['second'].get('stages') != [f'lesson{x}' for x in range(47, 53)]:
        raise LoopError('second phase stages must be lesson47 through lesson52 in order')
    if phases['second'].get('requires_phase') != 'first':
        raise LoopError('second phase must require completed first phase')
    if phases['second'].get('delivery_stage') != 'lesson53':
        raise LoopError('second phase delivery stage must be lesson53')
    if phases['first'].get('completion_message') != 'FIRST PHASE COMPLETE':
        raise LoopError('first phase completion_message must be FIRST PHASE COMPLETE')
    if phases['second'].get('completion_message') != 'SECOND PHASE COMPLETE':
        raise LoopError('second phase completion_message must be SECOND PHASE COMPLETE')
    stage_lookup = {stage['id']: stage for stage in stages}
    for phase_name, expected in (
        ('first', phases['first']['stages']),
        ('second', [*phases['second']['stages'], phases['second']['delivery_stage']]),
    ):
        for stage_id in expected:
            if stage_lookup[stage_id]['phase'] != phase_name:
                raise LoopError(f'{stage_id} must belong to phase {phase_name}')

    final_ids = _validate_judges(data['final_judges'], 'first final')
    candidate_ids = _validate_judges(data['release_candidate_judges'], 'release candidate')
    second_final_ids = _validate_judges(data['second_final_judges'], 'second final')
    candidate_contract = [
        (judge['id'], judge['command'], judge.get('owner_stages'))
        for judge in data['release_candidate_judges']
    ]
    if candidate_contract != RELEASE_CANDIDATE_CONTRACT:
        raise LoopError('release candidate judges must match the documented IDs, commands, and owners')
    second_final_contract = [
        (judge['id'], judge['command']) for judge in data['second_final_judges']
    ]
    if second_final_contract != SECOND_FINAL_CONTRACT:
        raise LoopError('second final judges must match the documented IDs and commands')
    if phases['first'].get('final_judges') != final_ids:
        raise LoopError('first phase final_judges must match final_judges order')
    if phases['second'].get('release_candidate_judges') != candidate_ids:
        raise LoopError('second phase release_candidate_judges must match configured order')
    if phases['second'].get('final_judges') != second_final_ids:
        raise LoopError('second phase final_judges must match second_final_judges order')
    second_owners = set(phases['second']['stages'])
    for judge in data['release_candidate_judges']:
        owners = judge.get('owner_stages')
        if not isinstance(owners, list) or not owners or not set(owners) <= second_owners:
            raise LoopError(f'release candidate judge {judge["id"]} has invalid owner_stages')

    if (
        data['git'].get('use_worktree') is not False
        or data['git'].get('merge_strategy') != 'no_ff'
        or data['git'].get('allow_push') is not False
    ):
        raise LoopError('Git policy must disable worktrees/push and require no_ff merges')
    runtime = data['runtime']
    if any(runtime.get(key) is not False for key in ('allow_docker', 'allow_docker_compose', 'allow_testcontainers')):
        raise LoopError('container runtimes must be disabled')

    visiting: set[str] = set()
    seen: set[str] = set()

    def visit(item: str) -> None:
        if item in visiting:
            raise LoopError('stage dependency cycle at ' + item)
        if item in seen:
            return
        if item not in stage_lookup:
            raise LoopError('unknown stage dependency ' + item)
        visiting.add(item)
        for dependency in stage_lookup[item]['depends_on']:
            visit(dependency)
        visiting.remove(item)
        seen.add(item)

    for stage_id in ids:
        visit(stage_id)
    for path in [*data['reference_docs'], data['orchestrator_spec'], data['common_contract'], *data['protected_paths']]:
        if '*' not in path and not (ROOT / path).exists():
            raise LoopError(f'missing required input: {path}')
    for stage in stages:
        for spec in stage['specs']:
            if not (ROOT / spec).exists():
                raise LoopError(f'missing stage specification: {spec}')


def load_config(config_path: str) -> dict[str, Any]:
    path = (ROOT / config_path).resolve()
    if ROOT not in path.parents:
        raise LoopError('config escapes repository')
    try:
        data = read_json(path)
    except Exception as exc:
        raise LoopError(f'loop.yaml must use JSON-compatible YAML: {exc}')
    validate_config(data)
    return data


def repo_dirty() -> list[str]:
    raw = git('status', '--porcelain', '-z', check=True).stdout
    paths = []
    for entry in raw.split('\0'):
        if not entry:
            continue
        path = entry[3:]
        if path.startswith('.loop/'):
            continue
        # Git collapses an untracked directory into one `?? directory/`
        # entry.  Boundary checks need its real file paths so a permitted
        # `.github/workflows/ci.yml` is not mistaken for all of `.github/`.
        candidate = ROOT / path
        if path.endswith('/') and candidate.is_dir():
            paths.extend(
                item.relative_to(ROOT).as_posix()
                for item in candidate.rglob('*')
                if item.is_file() and '.git' not in item.parts
            )
        else:
            paths.append(path)
    return sorted(set(paths))


def checked_branch() -> str:
    return git('branch', '--show-current').stdout.strip()


def resuming_stage_branch(branch: str, state: dict[str, Any], config: dict[str, Any]) -> bool:
    """Allow a recovered run only on the persisted active lesson branch."""
    current = state.get('current_stage')
    if state.get('status') != 'RUNNING' or state.get('current_branch') != branch or not current:
        return False
    record = state.get('stages', {}).get(current, {})
    if record.get('status') not in {'RUNNING', 'VERIFYING'}:
        return False
    return any(stage['id'] == current and stage['branch'] == branch for stage in config['stages'])


def protected_hash(config: dict[str, Any]) -> str:
    # A protected path explicitly owned by one stage is fingerprinted as that
    # stage's output. Excluding it here prevents lesson52's documented CI file
    # from retroactively invalidating earlier lessons while preserving all
    # boundary checks.
    allowed = [
        path
        for stage in config['stages']
        for path in stage.get('stage_protected_path_overrides', {}).get('allow_write', [])
    ]
    return path_hash(config['protected_paths'], allowed)


def stage_hashes(config: dict[str, Any], stage: dict[str, Any]) -> dict[str, str]:
    inputs = [*config['reference_docs'], config['common_contract'], config['orchestrator_spec'], *stage['specs']]
    return {
        'spec_hash': path_hash(inputs),
        'output_hash': path_hash(stage['fingerprint_paths']),
        'judge_hash': sha_bytes(json.dumps(stage['judge'], sort_keys=True).encode()),
        'protected_hash': protected_hash(config),
    }


def _phase_record(status: str = 'PENDING', run_id: str | None = None) -> dict[str, Any]:
    record = {'status': status, 'release_candidate_judges': {}, 'final_judges': {}}
    if run_id:
        record['run_id'] = run_id
    return record


def migrate_state(state: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    """Add phase records to the original schema-v1 state without discarding evidence."""
    phases = state.setdefault('phases', {})
    first_ids = config['phases']['first']['stages']
    legacy_first_complete = (
        state.get('status') == 'COMPLETE'
        and all(state.get('stages', {}).get(stage_id, {}).get('status') == 'PASSED' for stage_id in first_ids)
    )
    phases.setdefault('first', _phase_record(
        'COMPLETE' if legacy_first_complete else 'PENDING', state.get('run_id'),
    ))
    phases.setdefault('second', _phase_record())
    for phase_name in PHASE_ORDER:
        record = phases[phase_name]
        record.setdefault('status', 'PENDING')
        record.setdefault('release_candidate_judges', {})
        record.setdefault('final_judges', {})
    if state.get('run_id'):
        phases['first'].setdefault('run_id', state['run_id'])
    if legacy_first_complete:
        phases['first'].setdefault('migration', 'legacy COMPLETE state')
    state.setdefault('active_phase', None if state.get('status') == 'COMPLETE' else 'first')
    return state


def load_state(config: dict[str, Any]) -> dict[str, Any]:
    for candidate in (STATE, BACKUP):
        if candidate.exists():
            try:
                state = read_json(candidate)
                if state.get('schema_version') == 1:
                    before = json.dumps(state, sort_keys=True)
                    state = migrate_state(state, config)
                    if json.dumps(state, sort_keys=True) != before:
                        atomic_write(STATE, state, preserve_backup=candidate == STATE)
                    return state
            except Exception:
                pass
    run_id = dt.datetime.now().strftime('%Y%m%d-%H%M%S-') + uuid.uuid4().hex[:4]
    state = {
        'schema_version': 1,
        'run_id': run_id,
        'status': 'RUNNING',
        'source_root': str(ROOT),
        'base_branch': config['git']['base_branch'],
        'current_branch': checked_branch(),
        'master_head': git('rev-parse', 'HEAD').stdout.strip(),
        'current_stage': None,
        'active_phase': None,
        'started_at': now(),
        'updated_at': now(),
        'stages': {},
        'phases': {'first': _phase_record(run_id=run_id), 'second': _phase_record()},
    }
    return state


def save(state: dict[str, Any]) -> None:
    state['updated_at'] = now()
    atomic_write(STATE, state)


def matching(path: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)


def overlapping_patterns(left: list[str], right: list[str]) -> bool:
    """Return true for identical paths or glob roots declared as shared ownership."""
    for first in left:
        first_root = first.removesuffix('/**')
        for second in right:
            second_root = second.removesuffix('/**')
            if first == second or first_root == second_root:
                return True
    return False


def changed_paths() -> list[str]:
    return repo_dirty()


def command_log(
    command: list[str], directory: pathlib.Path, timeout: int,
    env: dict[str, str] | None = None,
) -> tuple[bool, str]:
    start = time.monotonic()
    try:
        result = shell(command, timeout=timeout, check=False, env=env)
    except subprocess.TimeoutExpired:
        (directory / 'judge.stdout.log').write_text('')
        (directory / 'judge.stderr.log').write_text('timeout')
        return False, f'timeout after {timeout}s'
    out = (result.stdout or '')[-MAX_LOG:]
    err = (result.stderr or '')[-MAX_LOG:]
    (directory / 'judge.stdout.log').write_text(out)
    (directory / 'judge.stderr.log').write_text(err)
    (directory / 'judge-result.json').write_text(json.dumps({
        'command': command,
        'exit_code': result.returncode,
        'duration_seconds': round(time.monotonic() - start, 3),
    }, indent=2))
    return result.returncode == 0, (err or out)[-4000:]


def redact(text: str) -> str:
    import re
    return re.sub(
        r'(?i)(password|token|secret|authorization|cookie)\s*[=:]\s*[^\s,]+',
        r'\1=[REDACTED]', text,
    )


class Runner:
    def __init__(self, config: dict[str, Any], phase: str = 'first'):
        if phase not in PHASE_ORDER:
            raise LoopError('unknown phase ' + phase)
        self.config = config
        self.phase = phase
        self.state = load_state(config)
        self.stop_requested = False

    @property
    def phase_config(self) -> dict[str, Any]:
        return self.config['phases'][self.phase]

    @property
    def phase_run_id(self) -> str:
        return self.state.get('phases', {}).get(self.phase, {}).get('run_id') or self.state['run_id']

    def stage(self, stage_id: str) -> dict[str, Any]:
        return next(stage for stage in self.config['stages'] if stage['id'] == stage_id)

    def phase_stages(self, include_delivery: bool = True) -> list[dict[str, Any]]:
        ids = list(self.phase_config['stages'])
        if include_delivery and self.phase_config.get('delivery_stage'):
            ids.append(self.phase_config['delivery_stage'])
        return [self.stage(stage_id) for stage_id in ids]

    def validate(self) -> None:
        validate_config(self.config)
        branch = checked_branch()
        if branch not in ('master', 'lesson/46') and not resuming_stage_branch(branch, self.state, self.config):
            raise LoopError('validate requires master, lesson/46, or the persisted active lesson branch', 4)
        dirty = repo_dirty()
        bootstrap_paths = ['AGENTS.md', 'loop', 'loop.yaml', '.gitignore', 'harness/']
        if dirty:
            if branch == 'lesson/46' and all(
                any(path == prefix or path.startswith(prefix) for prefix in bootstrap_paths)
                for path in dirty
            ):
                pass
            elif resuming_stage_branch(branch, self.state, self.config):
                active = self.stage(self.state['current_stage'])
                allowed_protected = active.get('stage_protected_path_overrides', {}).get('allow_write', [])
                invalid = [
                    path for path in dirty
                    if (
                        matching(path, self.config['protected_paths'])
                        and not matching(path, allowed_protected)
                    ) or not matching(path, active['write_paths'])
                ]
                if invalid:
                    raise LoopError(
                        'active lesson recovery contains out-of-scope changes: ' + ', '.join(invalid), 4,
                    )
            else:
                raise LoopError('working tree is not clean: ' + ', '.join(dirty), 4)
        for program in ('git', 'python3', 'codex', 'java', 'redis-server', 'etcd'):
            if not shutil.which(program):
                raise LoopError(f'host executable not found: {program}', 7)
        adapter = ROOT / self.config['agent']['adapter']
        result = shell(['python3', str(adapter), '--probe'], timeout=20, check=False)
        if result.returncode:
            raise LoopError('Agent Adapter unavailable: ' + result.stderr, 5)
        for stage in self.config['stages']:
            command = stage['judge']['command'][0]
            if stage['lesson'] == 39 or not (ROOT / 'mvnw').exists():
                continue
            if '/' in command and not (ROOT / command).exists():
                raise LoopError(f'judge entry does not exist: {command}')
        print('VALIDATE OK: first and second phase orchestration is configured.')

    def plan(self) -> None:
        validate_config(self.config)
        print(f'phase={self.phase}')
        normal_ids = set(self.phase_config['stages'])
        for stage in self.phase_stages():
            if self.phase == 'second' and stage['id'] not in normal_ids:
                print('release_candidate_judges:')
                for judge_id in self.phase_config['release_candidate_judges']:
                    judge = next(item for item in self.config['release_candidate_judges'] if item['id'] == judge_id)
                    print(f"gate {judge['id']} owners={','.join(judge['owner_stages'])} command={' '.join(judge['command'])}")
            record = self.state.get('stages', {}).get(stage['id'], {})
            print(
                f"{stage['lesson']} {stage['branch']} {record.get('status', 'PENDING')} "
                f"inputs={','.join(stage['specs'])} outputs={','.join(stage['write_paths'])} "
                f"judge={' '.join(stage['judge']['command'])}"
            )
        print('final_judges:')
        for judge in self.final_judges():
            print(f"final {judge['id']} command={' '.join(judge['command'])}")

    def valid_pass(self, stage: dict[str, Any]) -> bool:
        record = self.state['stages'].get(stage['id'])
        if not record or record.get('status') != 'PASSED':
            return False
        if any(self.state['stages'].get(item, {}).get('status') != 'PASSED' for item in stage['depends_on']):
            return False
        hashes = stage_hashes(self.config, stage)
        if any(record.get(key) != value for key, value in hashes.items()):
            return False
        if not record.get('lesson_commit') or not record.get('merge_commit'):
            return False
        return git('merge-base', '--is-ancestor', record['merge_commit'], 'master', check=False).returncode == 0

    def invalidate_if_needed(self, phase: str | None = None) -> None:
        phase_name = phase or self.phase
        phase_config = self.config['phases'][phase_name]
        ids = [*phase_config['stages']]
        if phase_config.get('delivery_stage'):
            ids.append(phase_config['delivery_stage'])
        invalid = False
        for stage_id in ids:
            stage = self.stage(stage_id)
            record = self.state['stages'].get(stage_id)
            if invalid or (record and record.get('status') == 'PASSED' and not self.valid_pass(stage)):
                if record and record.get('protected_hash') != protected_hash(self.config):
                    changed = git(
                        'diff', '--name-only', f"{record.get('merge_commit')}..master", '--',
                        *self.config['protected_paths'], check=False,
                    ).stdout.splitlines()
                    if changed:
                        raise LoopError(
                            f'ENVIRONMENT_ERROR protected input changed for {stage_id}: {", ".join(changed)}', 7,
                        )
                    record['protected_hash'] = protected_hash(self.config)
                    record['fingerprint_migration'] = 'runtime-only protected fingerprint migration'
                    if self.valid_pass(stage):
                        self.state['stages'][stage_id] = record
                        continue
                self.state['stages'][stage_id] = {
                    'lesson': stage['lesson'], 'branch': stage['branch'], 'status': 'PENDING',
                    'invalidation_reason': 'input/output fingerprint changed',
                }
                invalid = True
        if invalid:
            self.state['phases'][phase_name]['status'] = 'PENDING'
        save(self.state)

    def require_phase_prerequisite(self) -> None:
        required = self.phase_config.get('requires_phase')
        if not required:
            return
        record = self.state['phases'].get(required, {})
        stages = [self.stage(stage_id) for stage_id in self.config['phases'][required]['stages']]
        if record.get('status') != 'COMPLETE':
            raise LoopError(f'phase {self.phase} requires a valid completed {required} phase', 4)
        if all(self.valid_pass(stage) for stage in stages):
            return

        # A phase-aware Runner can be introduced after the first phase has
        # already been merged.  In that narrow migration case the legacy
        # records lack the current phase/configuration fingerprints, while
        # their course commits still prove the completed first phase.  Do not
        # accept unmerged or incomplete stages; record the migration so the
        # second phase remains traceable and begins at lesson47.
        def committed_pass(stage: dict[str, Any]) -> bool:
            stage_record = self.state['stages'].get(stage['id'], {})
            return (
                stage_record.get('status') == 'PASSED'
                and bool(stage_record.get('lesson_commit'))
                and bool(stage_record.get('merge_commit'))
                and git(
                    'merge-base', '--is-ancestor', stage_record['merge_commit'], 'master',
                    check=False,
                ).returncode == 0
            )

        if required == 'first' and all(committed_pass(stage) for stage in stages):
            record['fingerprint_migration'] = (
                'phase-aware Runner migration: first phase course and merge commits verified'
            )
            record['fingerprint_migrated_at'] = now()
            self.state['phases'][required] = record
            save(self.state)
            return
        raise LoopError(f'phase {self.phase} requires a valid completed {required} phase', 4)

    def require_compatible_active_phase(self) -> None:
        active = self.state.get('active_phase')
        if (
            active and active != self.phase
            and self.state.get('phases', {}).get(active, {}).get('status') == 'RUNNING'
        ):
            raise LoopError(f'phase {active} has an unfinished run; resume it before starting {self.phase}', 4)

    def activate_phase_run(self) -> None:
        record = self.state['phases'][self.phase]
        if not record.get('run_id'):
            record['run_id'] = dt.datetime.now().strftime('%Y%m%d-%H%M%S-') + uuid.uuid4().hex[:4]
        self.state['run_id'] = record['run_id']

    def acquire(self) -> None:
        LOOP_DIR.mkdir(exist_ok=True)
        self.lock_handle = open(LOCK, 'a+')
        try:
            fcntl.flock(self.lock_handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.lock_handle.close()
            del self.lock_handle
            raise LoopError('another Runner already holds .loop/run.lock', 4)
        self.lock_handle.seek(0)
        self.lock_handle.truncate()
        self.lock_handle.write(json.dumps({
            'phase': self.phase, 'pid': os.getpid(), 'run_id': self.state['run_id'], 'started_at': now(),
        }))
        self.lock_handle.flush()

    def release(self) -> None:
        if hasattr(self, 'lock_handle'):
            fcntl.flock(self.lock_handle, fcntl.LOCK_UN)
            self.lock_handle.close()

    def prepare_branch(self, stage: dict[str, Any]) -> None:
        if repo_dirty():
            raise LoopError('working tree contains external changes: ' + ', '.join(repo_dirty()), 4)
        git('switch', 'master')
        exists = git('show-ref', '--verify', '--quiet', f"refs/heads/{stage['branch']}", check=False).returncode == 0
        if exists:
            git('switch', stage['branch'])
            record = self.state.get('stages', {}).get(stage['id'], {})
            already_merged = git(
                'merge-base', '--is-ancestor', stage['branch'], 'master', check=False,
            ).returncode == 0
            if record.get('status') in {'PENDING', 'STALE'} and already_merged:
                # A fingerprint or candidate-gate invalidation reopens the
                # documented lesson branch on the newest master; an active
                # interrupted branch is left untouched for normal recovery.
                git('merge', '--no-edit', 'master')
        else:
            git('switch', '-c', stage['branch'], 'master')
        self.state['current_branch'] = stage['branch']
        self.state['current_stage'] = stage['id']
        save(self.state)

    def context(self, stage: dict[str, Any], attempt: int, folder: pathlib.Path) -> pathlib.Path:
        upstream = []
        for dependency in stage['depends_on']:
            handoff = self.state['stages'].get(dependency, {}).get('handoff')
            if handoff:
                upstream.append(handoff)
        inputs = [
            *self.config['reference_docs'], self.config['common_contract'],
            self.config['orchestrator_spec'], *stage['specs'], *upstream,
        ]
        manifest = {
            'run_id': self.state['run_id'], 'phase': self.phase,
            'stage': stage['id'], 'attempt': attempt,
            'files': [{'path': path, 'sha256': path_hash([path])} for path in inputs if (ROOT / path).exists()],
        }
        (folder / 'context-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
        previous = self.state['stages'].get(stage['id'], {}).get('last_failure', 'none')
        template = (ROOT / 'harness/loop/prompts/stage.md').read_text()
        protected = self.stage_protected_paths(stage)
        prompt = template.format(
            lesson=stage['lesson'], branch=stage['branch'], attempt=attempt,
            stage_goal=f"Implement only the deliverables of {stage['id']}.",
            spec_paths='\n'.join(f'- {path}' for path in inputs),
            upstream_handoffs='\n'.join(f'- {path}' for path in upstream) or '- none',
            write_paths=', '.join(stage['write_paths']), protected_paths=', '.join(protected),
            judge_command=' '.join(stage['judge']['command']), last_failure_or_none=previous,
        )
        request = folder / 'agent-request.md'
        request.write_text(prompt)
        return request

    def stage_protected_paths(self, stage: dict[str, Any]) -> list[str]:
        allowed = stage.get('stage_protected_path_overrides', {}).get('allow_write', [])
        return [pattern for pattern in self.config['protected_paths'] if pattern not in allowed]

    def check_boundary(self, stage: dict[str, Any]) -> tuple[bool, str]:
        bad = []
        allowed_protected = stage.get('stage_protected_path_overrides', {}).get('allow_write', [])
        for path in changed_paths():
            if matching(path, self.config['protected_paths']) and not matching(path, allowed_protected):
                bad.append(path + ' (protected)')
            elif not matching(path, stage['write_paths']):
                bad.append(path + ' (outside write_paths)')
        return not bad, ', '.join(bad)

    def services(self, stage: dict[str, Any], start: bool) -> None:
        services = stage.get('services', [])
        if not services:
            return
        scripts = self.config['runtime']['local_services']
        if start:
            result = shell([scripts['start'][0], *services], timeout=60, check=False)
            if result.returncode:
                raise LoopError('local dependency start failed: ' + result.stderr, 7)
            result = shell([*scripts['wait'], *services], timeout=45, check=False)
            if result.returncode:
                raise LoopError('local dependency wait failed: ' + result.stderr, 7)
        else:
            shell(scripts['stop'], timeout=30, check=False)

    def attempt(self, stage: dict[str, Any], number: int) -> tuple[bool, str]:
        folder = LOOP_DIR / 'runs' / self.state['run_id'] / stage['id'] / f'attempt-{number:03d}'
        folder.mkdir(parents=True, exist_ok=True)
        request = self.context(stage, number, folder)
        payload = {
            'run_id': self.state['run_id'], 'phase': self.phase,
            'stage': stage['id'], 'lesson': stage['lesson'], 'branch': stage['branch'],
            'attempt': number, 'workspace': str(ROOT), 'prompt_file': str(request),
            'context_manifest': str(folder / 'context-manifest.json'),
            'allowed_write_paths': stage['write_paths'],
            'timeout_seconds': self.config['agent']['timeout_seconds'],
            'environment_allowlist': self.config['agent']['environment_allowlist'],
        }
        result = subprocess.run(
            ['python3', str(ROOT / self.config['agent']['adapter'])],
            input=json.dumps(payload), text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            cwd=ROOT, timeout=self.config['agent']['timeout_seconds'] + 30,
        )
        try:
            agent = json.loads(result.stdout.splitlines()[-1])
        except Exception:
            agent = {'status': 'failed', 'summary': redact(result.stderr or result.stdout)}
        (folder / 'agent-result.json').write_text(json.dumps(agent, ensure_ascii=False, indent=2))
        (folder / 'agent-output.log').write_text(agent.get('raw_output', ''))
        (folder / 'changed-files.txt').write_text('\n'.join(changed_paths()) + '\n')
        ok, boundary = self.check_boundary(stage)
        if not ok:
            return False, 'boundary violation: ' + boundary
        if agent.get('status') != 'completed':
            return False, 'agent failure: ' + agent.get('summary', 'unknown')
        try:
            self.services(stage, True)
            env_file = ROOT / self.config['runtime']['local_services']['env_file']
            return command_log(
                stage['judge']['command'], folder,
                stage['judge'].get('timeout_seconds', self.config['defaults']['judge_timeout_seconds']),
                runtime_environment(env_file),
            )
        finally:
            self.services(stage, False)

    def pass_stage(self, stage: dict[str, Any], attempt: int) -> None:
        if not changed_paths():
            raise LoopError(f'{stage["id"]} passed without any staged implementation changes', 6)
        git('add', '--', *stage['write_paths'])
        git('commit', '-m', f"lesson {stage['lesson']}: implement course deliverables")
        lesson_commit = git('rev-parse', 'HEAD').stdout.strip()
        git('switch', 'master')
        git('merge', '--no-ff', stage['branch'], '-m', f"merge lesson {stage['lesson']}")
        merge_commit = git('rev-parse', 'HEAD').stdout.strip()
        handoff = LOOP_DIR / 'runs' / self.state['run_id'] / stage['id'] / 'handoff.md'
        handoff.parent.mkdir(parents=True, exist_ok=True)
        handoff.write_text(
            f"# Lesson {stage['lesson']} handoff\n\nlesson commit: {lesson_commit}\n"
            f'merge commit: {merge_commit}\nattempts: {attempt}\n'
        )
        self.state['stages'][stage['id']] = {
            'lesson': stage['lesson'], 'branch': stage['branch'], 'status': 'PASSED',
            'attempts': attempt, 'lesson_commit': lesson_commit, 'merge_commit': merge_commit,
            'passed_at': now(), 'handoff': str(handoff.relative_to(ROOT)),
            **stage_hashes(self.config, stage),
        }
        for upstream in self.config['stages']:
            if upstream['id'] == stage['id']:
                break
            record = self.state['stages'].get(upstream['id'], {})
            shares_output = overlapping_patterns(
                upstream.get('shared_write_paths', []), stage['write_paths'],
            ) or overlapping_patterns(
                stage.get('shared_write_paths', []), upstream['fingerprint_paths'],
            )
            if record.get('status') == 'PASSED' and shares_output:
                record['output_hash'] = path_hash(upstream['fingerprint_paths'])
                record['shared_fingerprint_refreshed_by'] = stage['id']
        self.state['current_branch'] = 'master'
        self.state['current_stage'] = None
        self.state['master_head'] = merge_commit
        save(self.state)
        print(f"PASS {stage['id']} branch={stage['branch']} merged=master attempt={attempt}")

    def run_stages(self, stages: list[dict[str, Any]]) -> None:
        for stage in stages:
            if self.valid_pass(stage):
                print(f"SKIPPED(PASSED) {stage['id']}")
                continue
            self.prepare_branch(stage)
            attempts = self.state['stages'].get(stage['id'], {}).get('attempts', 0)
            while True:
                attempts += 1
                record = self.state['stages'].setdefault(stage['id'], {})
                record.update({
                    'lesson': stage['lesson'], 'branch': stage['branch'],
                    'status': 'RUNNING', 'attempts': attempts,
                })
                save(self.state)
                ok, detail = self.attempt(stage, attempts)
                if ok:
                    self.pass_stage(stage, attempts)
                    break
                detail = redact(detail)
                record.update({
                    'status': 'VERIFYING', 'last_failure': detail,
                    'last_failure_fingerprint': sha_bytes(detail.encode()),
                    'last_attempt': f".loop/runs/{self.state['run_id']}/{stage['id']}/attempt-{attempts:03d}",
                })
                save(self.state)
                print(f"RETRY {stage['id']} attempt={attempts}: {detail[:240]}")
                if attempts % self.config['defaults']['attempts_per_batch'] == 0:
                    record['replan_batches'] = (
                        attempts // self.config['defaults']['attempts_per_batch']
                    )
                    save(self.state)

    def candidate_judges(self) -> list[dict[str, Any]]:
        ids = self.phase_config.get('release_candidate_judges', [])
        lookup = {judge['id']: judge for judge in self.config['release_candidate_judges']}
        return [lookup[judge_id] for judge_id in ids]

    def final_judges(self) -> list[dict[str, Any]]:
        source = self.config['final_judges'] if self.phase == 'first' else self.config['second_final_judges']
        lookup = {judge['id']: judge for judge in source}
        return [lookup[judge_id] for judge_id in self.phase_config['final_judges']]

    def run_judges(self, kind: str, judges: list[dict[str, Any]]) -> tuple[bool, list[dict[str, Any]]]:
        folder = LOOP_DIR / 'runs' / self.state['run_id'] / self.phase / kind
        folder.mkdir(parents=True, exist_ok=True)
        self.state['phases'][self.phase][kind] = {}
        save(self.state)
        results = []
        failure = None
        for judge in judges:
            if failure and not judge.get('always_run'):
                continue
            judge_dir = folder / judge['id']
            judge_dir.mkdir(parents=True, exist_ok=True)
            self.state['current_stage'] = None
            self.state['phases'][self.phase]['current_gate'] = judge['id']
            save(self.state)
            env_file = ROOT / self.config['runtime']['local_services']['env_file']
            ok, detail = command_log(
                judge['command'], judge_dir, judge.get('timeout_seconds', 1200),
                runtime_environment(env_file),
            )
            result = {'id': judge['id'], 'passed': ok, 'detail': redact(detail), 'finished_at': now()}
            results.append(result)
            self.state['phases'][self.phase][kind][judge['id']] = result
            save(self.state)
            if not ok and not judge.get('always_run') and failure is None:
                failure = judge
        self.state['phases'][self.phase].pop('current_gate', None)
        save(self.state)
        return failure is None, results

    def invalidate_candidate_failure(self, judge: dict[str, Any]) -> None:
        ordered = self.phase_config['stages']
        first_owner = min(ordered.index(owner) for owner in judge['owner_stages'])
        stale_ids = ordered[first_owner:]
        if self.phase_config.get('delivery_stage'):
            stale_ids = [*stale_ids, self.phase_config['delivery_stage']]
        phase_record = self.state['phases'][self.phase]
        failure = phase_record.get('release_candidate_judges', {}).get(judge['id'], {})
        detail = redact(failure.get('detail', f'release candidate judge {judge["id"]} failed'))
        for stage_id in stale_ids:
            stage = self.stage(stage_id)
            record = self.state['stages'].setdefault(stage_id, {})
            record.update({
                'lesson': stage['lesson'], 'branch': stage['branch'], 'status': 'STALE',
                'invalidation_reason': f'release candidate judge {judge["id"]} failed; owners={",".join(judge["owner_stages"])}',
                'last_failure': detail,
                'last_failure_fingerprint': sha_bytes(detail.encode()),
            })
        phase_record['status'] = 'RUNNING'
        phase_record['candidate_failure'] = judge['id']
        save(self.state)

    def report_path(self) -> pathlib.Path:
        name = 'final-report.md' if self.phase == 'first' else 'second-final-report.md'
        return LOOP_DIR / 'runs' / self.phase_run_id / name

    def write_report(self) -> pathlib.Path:
        report = self.report_path()
        report.parent.mkdir(parents=True, exist_ok=True)
        phase_record = self.state['phases'][self.phase]
        lines = [
            '# When Loop final report', '', f'phase: {self.phase}',
            f"run_id: {self.phase_run_id}", f"status: {phase_record.get('status', 'PENDING')}",
            '', '## Lessons',
        ]
        for stage in self.phase_stages():
            record = self.state['stages'].get(stage['id'], {})
            lines.append(
                f"- {stage['id']}: {record.get('status', 'PENDING')}; "
                f"lesson={record.get('lesson_commit', '-')}; merge={record.get('merge_commit', '-')}; "
                f"attempts={record.get('attempts', 0)}"
            )
        if self.phase_config.get('release_candidate_judges'):
            lines.extend(['', '## Release candidate judges'])
            saved = phase_record.get('release_candidate_judges', {})
            for judge in self.candidate_judges():
                result = saved.get(judge['id'], {})
                lines.append(f"- {judge['id']}: {'PASS' if result.get('passed') else 'PENDING/FAIL'}")
        lines.extend(['', '## Final judges'])
        saved = phase_record.get('final_judges', {})
        for judge in self.final_judges():
            result = saved.get(judge['id'], {})
            lines.append(f"- {judge['id']}: {'PASS' if result.get('passed') else 'PENDING/FAIL'}")
        report.write_text('\n'.join(lines) + '\n')
        phase_record['report'] = str(report.relative_to(ROOT))
        save(self.state)
        return report

    def complete_phase(self, results: list[dict[str, Any]]) -> None:
        phase_record = self.state['phases'][self.phase]
        phase_record['status'] = 'COMPLETE'
        phase_record['completed_at'] = now()
        self.state['status'] = 'COMPLETE'
        self.state['active_phase'] = None
        self.state['current_branch'] = 'master'
        self.state['current_stage'] = None
        save(self.state)
        report = self.write_report()
        passed = sum(result['passed'] for result in results)
        lessons = len(self.phase_stages())
        print(
            f"{self.phase_config['completion_message']}\nphase: {self.phase}\n"
            f"run_id: {self.phase_run_id}\nlessons: {lessons}/{lessons} passed\n"
            f"final_judges: {passed}/{len(results)} passed\nreport: {report.relative_to(ROOT)}"
        )

    def run(self) -> int:
        self.validate()
        self.acquire()
        try:
            self.require_compatible_active_phase()
            self.require_phase_prerequisite()
            self.invalidate_if_needed()
            self.activate_phase_run()
            phase_record = self.state['phases'][self.phase]
            phase_record['status'] = 'RUNNING'
            phase_record.setdefault('started_at', now())
            self.state['status'] = 'RUNNING'
            self.state['active_phase'] = self.phase
            save(self.state)
            normal_stages = self.phase_stages(include_delivery=False)
            if self.phase == 'second':
                while True:
                    self.run_stages(normal_stages)
                    git('switch', 'master')
                    candidates = self.candidate_judges()
                    ok, results = self.run_judges('release_candidate_judges', candidates)
                    if ok:
                        phase_record['release_candidate_passed_at'] = now()
                        save(self.state)
                        break
                    failed = next(judge for judge in candidates if not next(
                        result['passed'] for result in results if result['id'] == judge['id']
                    ))
                    self.invalidate_candidate_failure(failed)
                self.run_stages([self.stage(self.phase_config['delivery_stage'])])
            else:
                self.run_stages(normal_stages)
            git('switch', 'master')
            phase_record['final_judges'] = {}
            save(self.state)
            self.write_report()
            ok, results = self.run_judges('final_judges', self.final_judges())
            self.write_report()
            if not ok:
                return 6
            self.complete_phase(results)
            return 0
        finally:
            self.release()

    def status(self, json_output: bool = False) -> None:
        phase_record = self.state['phases'][self.phase]
        if json_output:
            output = {
                'phase': self.phase,
                'run_id': self.phase_run_id,
                'status': phase_record.get('status', 'PENDING'),
                'current_stage': self.state.get('current_stage') if self.state.get('active_phase') == self.phase else None,
                'current_gate': phase_record.get('current_gate'),
                'stages': {stage['id']: self.state.get('stages', {}).get(stage['id'], {'status': 'PENDING'}) for stage in self.phase_stages()},
                'release_candidate_judges': phase_record.get('release_candidate_judges', {}),
                'final_judges': phase_record.get('final_judges', {}),
            }
            print(json.dumps(output, ensure_ascii=False, indent=2))
            return
        current = phase_record.get('current_gate') or (
            self.state.get('current_stage') if self.state.get('active_phase') == self.phase else None
        )
        print(
            f"phase={self.phase} run_id={self.phase_run_id} "
            f"status={phase_record.get('status', 'PENDING')} current={current}"
        )
        for stage in self.phase_stages():
            record = self.state.get('stages', {}).get(stage['id'], {})
            print(
                f"{stage['id']} {record.get('status', 'PENDING')} "
                f"branch={stage['branch']} attempts={record.get('attempts', 0)}"
            )

    def logs(self, stage: str, attempt: int | None) -> None:
        record = self.state.get('stages', {}).get(stage, {})
        target = LOOP_DIR / 'runs' / self.state['run_id'] / stage / (
            f'attempt-{attempt:03d}' if attempt else pathlib.Path(record.get('last_attempt', 'x')).name
        )
        for name in ('agent-result.json', 'judge.stderr.log', 'judge.stdout.log', 'attempt-summary.md'):
            path = target / name
            if path.exists():
                print(f'## {name}\n{path.read_text()[-8000:]}')

    def invalidate(self, stage_id: str, downstream: bool) -> None:
        stages = self.config['stages']
        found = False
        for stage in stages:
            if stage['id'] == stage_id:
                found = True
            if found:
                if stage['id'] != stage_id and not downstream:
                    break
                self.state['stages'][stage['id']] = {
                    'lesson': stage['lesson'], 'branch': stage['branch'], 'status': 'PENDING',
                    'invalidation_reason': 'manual invalidation',
                }
                self.state['phases'][stage['phase']]['status'] = 'PENDING'
        if not found:
            raise LoopError('unknown stage ' + stage_id)
        save(self.state)
        print('invalidated ' + stage_id + (' and downstream' if downstream else ''))

    def report(self) -> None:
        print(self.write_report().relative_to(ROOT))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', default='loop.yaml')
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('validate')
    for command in ('plan', 'run'):
        item = sub.add_parser(command)
        item.add_argument('--phase', choices=PHASE_ORDER, default='first')
        item.add_argument('--config', default=argparse.SUPPRESS)
    status = sub.add_parser('status')
    status.add_argument('--phase', choices=PHASE_ORDER, default='first')
    status.add_argument('--config', default=argparse.SUPPRESS)
    status.add_argument('--json', action='store_true')
    logs = sub.add_parser('logs')
    logs.add_argument('--stage', required=True)
    logs.add_argument('--attempt', type=int)
    invalidate = sub.add_parser('invalidate')
    invalidate.add_argument('--stage', required=True)
    invalidate.add_argument('--downstream', action='store_true')
    report = sub.add_parser('report')
    report.add_argument('--phase', choices=PHASE_ORDER, default='first')
    report.add_argument('--config', default=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        phase = getattr(args, 'phase', 'first')
        instance = Runner(load_config(args.config), phase)
        if args.command == 'validate':
            instance.validate()
            return 0
        if args.command == 'plan':
            instance.plan()
            return 0
        if args.command == 'run':
            return instance.run()
        if args.command == 'status':
            instance.status(args.json)
            return 0
        if args.command == 'logs':
            instance.logs(args.stage, args.attempt)
            return 0
        if args.command == 'invalidate':
            instance.invalidate(args.stage, args.downstream)
            return 0
        if args.command == 'report':
            instance.report()
            return 0
    except LoopError as exc:
        print(str(exc), file=sys.stderr)
        return exc.code
    except KeyboardInterrupt:
        print('INTERRUPTED', file=sys.stderr)
        return 130
    except Exception as exc:
        print('RUNNER_INTERNAL_ERROR ' + str(exc), file=sys.stderr)
        return 10
    return 10


if __name__ == '__main__':
    raise SystemExit(main())
