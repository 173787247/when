from __future__ import annotations
import json
import pathlib
import sys
import tempfile
import unittest
import fcntl
from contextlib import redirect_stdout
from io import StringIO
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

    def test_config_rejects_changed_documented_gate_command(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        config['release_candidate_judges'][0]['command'] = ['not-the-documented-command']
        with self.assertRaises(runner.LoopError):
            runner.validate_config(config)

    def test_config_declares_documented_phase_order_and_judges(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        runner.validate_config(config)
        self.assertEqual(
            config['phases']['first']['stages'],
            [f'lesson{lesson}' for lesson in range(39, 46)],
        )
        self.assertEqual(
            config['phases']['second']['stages'],
            [f'lesson{lesson}' for lesson in range(47, 53)],
        )
        self.assertEqual(config['phases']['second']['delivery_stage'], 'lesson53')
        self.assertEqual(
            config['phases']['second']['release_candidate_judges'],
            [
                'second_full_integration',
                'second_ha_failover',
                'second_observability',
                'second_release_artifacts',
            ],
        )
        self.assertEqual(
            config['phases']['second']['final_judges'],
            [
                'second_full_regression',
                'second_documentation_smoke',
                'second_release_report_complete',
            ],
        )
        judges = [*config['release_candidate_judges'], *config['second_final_judges']]
        self.assertTrue(all(isinstance(judge['command'], list) for judge in judges))

    def test_second_phase_branches_specs_and_write_paths_are_narrow(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        second = [stage for stage in config['stages'] if stage['phase'] == 'second']
        self.assertEqual([stage['branch'] for stage in second], [f'lesson/{x}' for x in range(47, 54)])
        self.assertTrue(all(stage['specs'][0].startswith(f"docs/第{stage['lesson']}节") for stage in second))
        for stage in second:
            self.assertNotIn('**', stage['write_paths'])
            self.assertNotIn('.', stage['write_paths'])
        lesson52 = next(stage for stage in second if stage['id'] == 'lesson52')
        self.assertEqual(
            lesson52['stage_protected_path_overrides']['allow_write'],
            ['.github/workflows/ci.yml'],
        )

    def test_second_plan_orders_release_gate_before_delivery_and_final_judges(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        instance = object.__new__(runner.Runner)
        instance.config = config
        instance.phase = 'second'
        instance.state = {'phases': {'second': {}}, 'stages': {}}
        output = StringIO()
        with mock.patch.object(instance, 'validate'), redirect_stdout(output):
            instance.plan()
        plan = output.getvalue()
        self.assertLess(plan.index('52 lesson/52'), plan.index('gate second_full_integration'))
        self.assertLess(plan.index('gate second_full_integration'), plan.index('53 lesson/53'))
        self.assertLess(plan.index('53 lesson/53'), plan.index('final second_full_regression'))

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

    def test_shared_path_overlap_accepts_exact_path_and_glob_root(self):
        self.assertTrue(runner.overlapping_patterns(['DEPLOY.md'], ['DEPLOY.md']))
        self.assertTrue(runner.overlapping_patterns(['deploy/examples/**'], ['deploy/examples/**']))
        self.assertFalse(runner.overlapping_patterns(['deploy/examples/**'], ['deploy/k8s/**']))

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

    def test_fingerprint_ignores_maven_build_artifacts(self):
        artifact = ROOT / 'when-common/target/fingerprint-test.txt'
        artifact.parent.mkdir(exist_ok=True)
        before = runner.path_hash(['when-common/**'])
        artifact.write_text('ephemeral')
        after = runner.path_hash(['when-common/**'])
        artifact.unlink()
        self.assertEqual(before, after)

    def test_runtime_environment_reads_local_service_values(self):
        with tempfile.TemporaryDirectory() as temp:
            env_file = pathlib.Path(temp) / 'runtime.env'
            env_file.write_text("WHEN_ETCD_ENDPOINTS='http://127.0.0.1:2379'\nWHEN_REDIS_PORT=6380\n")
            self.assertEqual(
                runner.runtime_environment(env_file),
                {'WHEN_ETCD_ENDPOINTS': 'http://127.0.0.1:2379', 'WHEN_REDIS_PORT': '6380'},
            )

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

    def test_legacy_complete_state_migrates_to_phase_records(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        legacy = {
            'schema_version': 1,
            'status': 'COMPLETE',
            'stages': {
                stage_id: {'status': 'PASSED'}
                for stage_id in config['phases']['first']['stages']
            },
        }
        migrated = runner.migrate_state(legacy, config)
        self.assertEqual(migrated['phases']['first']['status'], 'COMPLETE')
        self.assertEqual(migrated['phases']['second']['status'], 'PENDING')
        self.assertIsNone(migrated['active_phase'])
        self.assertEqual(migrated['stages']['lesson39']['status'], 'PASSED')

    def test_loading_legacy_state_persists_phase_migration(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        original_state, original_backup = runner.STATE, runner.BACKUP
        with tempfile.TemporaryDirectory() as temp:
            directory = pathlib.Path(temp)
            runner.STATE, runner.BACKUP = directory / 'state.json', directory / 'state.json.bak'
            runner.STATE.write_text(json.dumps({
                'schema_version': 1,
                'status': 'COMPLETE',
                'stages': {
                    stage_id: {'status': 'PASSED'}
                    for stage_id in config['phases']['first']['stages']
                },
            }))
            loaded = runner.load_state(config)
            persisted = json.loads(runner.STATE.read_text())
            self.assertEqual(loaded['phases']['first']['status'], 'COMPLETE')
            self.assertEqual(persisted['phases']['second']['status'], 'PENDING')
        runner.STATE, runner.BACKUP = original_state, original_backup

    def test_second_phase_requires_valid_completed_first(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        instance = object.__new__(runner.Runner)
        instance.config = config
        instance.phase = 'second'
        instance.state = {
            'phases': {'first': {'status': 'COMPLETE'}},
            'stages': {},
        }
        with mock.patch.object(instance, 'valid_pass', return_value=True):
            instance.require_phase_prerequisite()
        instance.state['phases']['first']['status'] = 'PENDING'
        with mock.patch.object(instance, 'valid_pass', return_value=True):
            with self.assertRaises(runner.LoopError):
                instance.require_phase_prerequisite()
        instance.state['phases']['first']['status'] = 'COMPLETE'
        with mock.patch.object(instance, 'valid_pass', return_value=False):
            with self.assertRaises(runner.LoopError):
                instance.require_phase_prerequisite()

    def test_second_phase_accepts_auditable_legacy_first_phase_migration(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        instance = object.__new__(runner.Runner)
        instance.config = config
        instance.phase = 'second'
        instance.state = {
            'phases': {'first': {'status': 'COMPLETE'}, 'second': {'status': 'PENDING'}},
            'stages': {
                f'lesson{lesson}': {
                    'status': 'PASSED',
                    'lesson_commit': f'lesson-{lesson}',
                    'merge_commit': f'merge-{lesson}',
                }
                for lesson in range(39, 46)
            },
        }
        with (
            mock.patch.object(instance, 'valid_pass', return_value=False),
            mock.patch.object(runner, 'git', return_value=mock.Mock(returncode=0)),
            mock.patch.object(runner, 'save') as save,
        ):
            instance.require_phase_prerequisite()
        self.assertIn('phase-aware Runner migration', instance.state['phases']['first']['fingerprint_migration'])
        save.assert_called_once_with(instance.state)

    def test_unfinished_other_phase_blocks_run(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        instance = object.__new__(runner.Runner)
        instance.config = config
        instance.phase = 'first'
        instance.state = {
            'active_phase': 'second',
            'phases': {'second': {'status': 'RUNNING'}},
        }
        with self.assertRaises(runner.LoopError):
            instance.require_compatible_active_phase()
        instance.state['phases']['second']['status'] = 'COMPLETE'
        instance.require_compatible_active_phase()

    def test_candidate_failure_invalidates_owner_and_downstream(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        instance = object.__new__(runner.Runner)
        instance.config = config
        instance.phase = 'second'
        instance.state = {
            'phases': {'second': {'status': 'RUNNING'}},
            'stages': {
                f'lesson{lesson}': {'status': 'PASSED'}
                for lesson in range(47, 54)
            },
        }
        judge = next(
            item for item in config['release_candidate_judges']
            if item['id'] == 'second_observability'
        )
        with mock.patch.object(runner, 'save'):
            instance.invalidate_candidate_failure(judge)
        self.assertEqual(instance.state['stages']['lesson49']['status'], 'PASSED')
        for stage_id in ('lesson50', 'lesson51', 'lesson52', 'lesson53'):
            self.assertEqual(instance.state['stages'][stage_id]['status'], 'STALE')
            self.assertIn('second_observability', instance.state['stages'][stage_id]['invalidation_reason'])

    def test_lesson52_can_write_only_documented_protected_ci_path(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        instance = object.__new__(runner.Runner)
        instance.config = config
        lesson52 = next(stage for stage in config['stages'] if stage['id'] == 'lesson52')
        with mock.patch.object(runner, 'changed_paths', return_value=['.github/workflows/ci.yml']):
            self.assertEqual(instance.check_boundary(lesson52), (True, ''))
        with mock.patch.object(runner, 'changed_paths', return_value=['harness/contracts/acceptance.sh']):
            ok, detail = instance.check_boundary(lesson52)
            self.assertFalse(ok)
            self.assertIn('protected', detail)

    def test_second_completion_prints_documented_message(self):
        config = runner.read_json(ROOT / 'loop.yaml')
        instance = object.__new__(runner.Runner)
        instance.config = config
        instance.phase = 'second'
        instance.state = {
            'run_id': 'phase-test',
            'phases': {'second': {'status': 'RUNNING'}},
            'stages': {},
        }
        output = StringIO()
        fake_report = ROOT / '.loop/runs/phase-test/second-final-report.md'
        with (
            mock.patch.object(runner, 'save'),
            mock.patch.object(instance, 'write_report', return_value=fake_report),
            redirect_stdout(output),
        ):
            instance.complete_phase([{'passed': True}, {'passed': True}, {'passed': True}])
        self.assertIn('SECOND PHASE COMPLETE', output.getvalue())
        self.assertIn('phase: second', output.getvalue())

    def test_run_subcommand_dispatches_to_runner(self):
        with mock.patch.object(sys, 'argv', ['loop_runner.py', 'run']), mock.patch.object(runner.Runner, 'run', return_value=0) as run:
            self.assertEqual(runner.main(), 0)
            run.assert_called_once()

    def test_phase_cli_dispatches_plan_status_report_and_run(self):
        commands = {
            'plan': ('plan', ()),
            'status': ('status', (False,)),
            'report': ('report', ()),
            'run': ('run', ()),
        }
        for command, (method_name, call_args) in commands.items():
            with self.subTest(command=command):
                argv = ['loop_runner.py', command, '--phase', 'second']
                with (
                    mock.patch.object(sys, 'argv', argv),
                    mock.patch.object(runner, 'load_config', return_value=runner.read_json(ROOT / 'loop.yaml')),
                    mock.patch.object(runner, 'load_state', return_value={
                        'run_id': 'cli-test', 'phases': {'first': {}, 'second': {}}, 'stages': {},
                    }),
                    mock.patch.object(runner.Runner, method_name, return_value=0) as called,
                ):
                    self.assertEqual(runner.main(), 0)
                    called.assert_called_once_with(*call_args)
                    self.assertEqual(called.call_args.args, call_args)

    def test_config_option_is_accepted_after_phase_subcommand(self):
        with (
            mock.patch.object(sys, 'argv', [
                'loop_runner.py', 'status', '--phase', 'second', '--config', 'loop.yaml',
            ]),
            mock.patch.object(runner, 'load_config', return_value=runner.read_json(ROOT / 'loop.yaml')) as load,
            mock.patch.object(runner, 'load_state', return_value={
                'run_id': 'cli-test', 'phases': {'first': {}, 'second': {}}, 'stages': {},
            }),
            mock.patch.object(runner.Runner, 'status') as status,
        ):
            self.assertEqual(runner.main(), 0)
            load.assert_called_once_with('loop.yaml')
            status.assert_called_once_with(False)

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
