from __future__ import annotations
import json
import pathlib
import sys
import tempfile
import unittest
import fcntl
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'harness/loop'))
import loop_runner as runner

class LoopRunnerTests(unittest.TestCase):
    def test_config_requires_exact_official_stage_order(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        config['stages'] = config['stages'][:-1]
        with self.assertRaises(runner.LoopError):
            runner.validate_config(config)

    def test_config_rejects_unknown_field(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        config['unapproved'] = True
        with self.assertRaises(runner.LoopError):
            runner.validate_config(config)

    def test_atomic_write_preserves_parseable_state_and_backup(self):
        original_state, original_backup = runner.STATE, runner.BACKUP
        with tempfile.TemporaryDirectory() as temp:
            directory = pathlib.Path(temp)
            runner.STATE, runner.BACKUP = directory / 'state.json', directory / 'state.json.bak'
            runner.atomic_write(runner.STATE, {'schema_version': 1, 'run_id': 'one'})
            runner.atomic_write(runner.STATE, {'schema_version': 1, 'run_id': 'two'})
            self.assertEqual(json.loads(runner.STATE.read_text())['run_id'], 'two')
            self.assertEqual(json.loads(runner.BACKUP.read_text())['run_id'], 'one')
        runner.STATE, runner.BACKUP = original_state, original_backup

    def test_write_path_scope(self):
        self.assertTrue(runner.matching('when-common/src/main/java/A.java', ['when-common/**']))
        self.assertFalse(runner.matching('docs/x.md', ['when-common/**']))
        self.assertTrue(runner.matching('when-api/pom.xml', ['*/pom.xml']))

    def test_stage_hash_includes_contract_and_outputs(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        hashes = runner.stage_hashes(config, config['stages'][0])
        self.assertEqual(set(hashes), {'spec_hash','output_hash','judge_hash','protected_hash'})
        self.assertTrue(all(value.startswith('sha256:') for value in hashes.values()))

    def test_shared_reactor_pom_is_not_a_stage_output_fingerprint(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        for stage in config['stages']:
            self.assertNotIn('pom.xml', stage['fingerprint_paths'])

    def test_fingerprint_ignores_python_runtime_bytecode(self):
        cache = ROOT / 'harness/contracts/__pycache__/fingerprint-test.pyc'
        cache.parent.mkdir(exist_ok=True)
        before = runner.path_hash(['harness/contracts/**'])
        cache.write_bytes(b'ephemeral')
        after = runner.path_hash(['harness/contracts/**'])
        cache.unlink()
        self.assertEqual(before, after)

    def test_second_lock_holder_is_rejected(self):
        original_loop_dir, original_lock = runner.LOOP_DIR, runner.LOCK
        with tempfile.TemporaryDirectory() as temp:
            runner.LOOP_DIR, runner.LOCK = pathlib.Path(temp), pathlib.Path(temp) / 'run.lock'
            handle = open(runner.LOCK, 'a+')
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            config = runner.read_json(ROOT / 'loop.yaml')
            instance = object.__new__(runner.Runner)
            instance.config = config
            instance.state = {'run_id': 'lock-test'}
            with self.assertRaises(runner.LoopError):
                instance.acquire()
            fcntl.flock(handle, fcntl.LOCK_UN)
            handle.close()
        runner.LOOP_DIR, runner.LOCK = original_loop_dir, original_lock

    def test_run_subcommand_dispatches_to_runner(self):
        with mock.patch.object(sys, 'argv', ['loop_runner.py', 'run']), mock.patch.object(runner.Runner, 'run', return_value=0) as run:
            self.assertEqual(runner.main(), 0)
            run.assert_called_once()

    def test_only_persisted_active_stage_branch_can_resume(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        state = {
            'status': 'RUNNING',
            'current_branch': 'lesson/42',
            'current_stage': 'lesson42',
            'stages': {'lesson42': {'status': 'VERIFYING'}},
        }
        self.assertTrue(runner.resuming_stage_branch('lesson/42', state, config))
        self.assertFalse(runner.resuming_stage_branch('lesson/43', state, config))
        state['stages']['lesson42']['status'] = 'PASSED'
        self.assertFalse(runner.resuming_stage_branch('lesson/42', state, config))

if __name__ == '__main__':
    unittest.main()
