#!/usr/bin/env python3
"""Stateful, single-repository Runner for the first When Loop."""
from __future__ import annotations
import argparse, datetime as dt, fcntl, fnmatch, hashlib, json, os, pathlib, shutil, signal, subprocess, sys, tempfile, time, uuid
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOOP_DIR = ROOT / '.loop'
STATE = LOOP_DIR / 'state.json'
BACKUP = LOOP_DIR / 'state.json.bak'
LOCK = LOOP_DIR / 'run.lock'
MAX_LOG = 10 * 1024 * 1024
TOP_KEYS = {'version','project','git','runtime','agent','defaults','reference_docs','orchestrator_spec','common_contract','protected_paths','stages','final_judges'}
STAGE_KEYS = {'id','lesson','branch','specs','depends_on','services','write_paths','shared_write_paths','fingerprint_paths','judge'}

class LoopError(RuntimeError):
    def __init__(self, message: str, code: int=3): super().__init__(message); self.code=code

def now() -> str: return dt.datetime.now(dt.timezone.utc).isoformat()
def shell(cmd: list[str], *, timeout: int=1200, check: bool=True, capture: bool=True, env: dict[str,str]|None=None) -> subprocess.CompletedProcess:
    runenv = os.environ.copy(); runenv.update(env or {})
    result=subprocess.run(cmd, cwd=ROOT, text=True, stdout=subprocess.PIPE if capture else None, stderr=subprocess.PIPE if capture else None, timeout=timeout, env=runenv)
    if check and result.returncode: raise LoopError(f'command failed ({result.returncode}): {" ".join(cmd)}\n{result.stderr[-2000:]}', 4)
    return result
def git(*args: str, **kwargs: Any) -> subprocess.CompletedProcess: return shell(['git',*args], **kwargs)
def sha_bytes(data: bytes) -> str: return 'sha256:'+hashlib.sha256(data).hexdigest()
def path_hash(patterns: list[str]) -> str:
    entries=[]
    for path in sorted(ROOT.rglob('*')):
        if not path.is_file() or '.git' in path.parts or '.loop' in path.parts or 'target' in path.parts or '__pycache__' in path.parts or path.suffix in {'.pyc','.pyo'}: continue
        rel=path.relative_to(ROOT).as_posix()
        if any(fnmatch.fnmatchcase(rel, p) for p in patterns): entries.append(rel.encode()+b'\0'+hashlib.sha256(path.read_bytes()).digest())
    return sha_bytes(b''.join(entries))
def read_json(path: pathlib.Path) -> dict[str,Any]: return json.loads(path.read_text(encoding='utf-8'))
def atomic_write(path: pathlib.Path, data: dict[str,Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded=(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True)+'\n').encode()
    fd, temp=tempfile.mkstemp(prefix=f'.{path.name}.', dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as handle: handle.write(encoded); handle.flush(); os.fsync(handle.fileno())
        if path.exists(): shutil.copy2(path, BACKUP)
        os.replace(temp,path)
        directory=os.open(path.parent,os.O_RDONLY); os.fsync(directory); os.close(directory)
    finally:
        if os.path.exists(temp): os.unlink(temp)
def load_config(config_path: str) -> dict[str,Any]:
    path=(ROOT/config_path).resolve()
    if ROOT not in path.parents: raise LoopError('config escapes repository')
    try: data=read_json(path)
    except Exception as exc: raise LoopError(f'loop.yaml must use JSON-compatible YAML: {exc}')
    validate_config(data); return data
def validate_config(data: dict[str,Any]) -> None:
    if not isinstance(data,dict): raise LoopError('configuration must be an object')
    unknown=set(data)-TOP_KEYS
    if unknown: raise LoopError('unknown configuration fields: '+', '.join(sorted(unknown)))
    missing=TOP_KEYS-set(data)
    if missing: raise LoopError('missing configuration fields: '+', '.join(sorted(missing)))
    if data['version']!=1: raise LoopError('only configuration version 1 is supported')
    stages=data['stages']; ids=[]; lessons=[]
    for stage in stages:
        if set(stage)-STAGE_KEYS: raise LoopError(f"unknown field in stage {stage.get('id')}: {set(stage)-STAGE_KEYS}")
        for key in ('id','lesson','branch','specs','depends_on','write_paths','fingerprint_paths','judge'):
            if key not in stage: raise LoopError(f"stage missing {key}: {stage}")
        if not isinstance(stage['judge'].get('command'),list) or not stage['judge']['command']: raise LoopError(f"stage {stage['id']} judge command must be an array")
        ids.append(stage['id']); lessons.append(stage['lesson'])
    if ids != ['lesson39','lesson40','lesson41','lesson42','lesson43','lesson44','lesson45'] or lessons != [39,40,41,42,43,44,45]: raise LoopError('official stages must be lesson39 through lesson45 in order')
    if data['git'].get('use_worktree') is not False or data['git'].get('merge_strategy')!='no_ff' or data['git'].get('allow_push') is not False: raise LoopError('Git policy must disable worktrees/push and require no_ff merges')
    runtime=data['runtime']
    if any(runtime.get(key) is not False for key in ('allow_docker','allow_docker_compose','allow_testcontainers')): raise LoopError('container runtimes must be disabled')
    lookup={s['id']:s for s in stages}; visiting=set(); seen=set()
    def visit(item: str):
        if item in visiting: raise LoopError('stage dependency cycle at '+item)
        if item in seen: return
        if item not in lookup: raise LoopError('unknown stage dependency '+item)
        visiting.add(item); [visit(x) for x in lookup[item]['depends_on']]; visiting.remove(item); seen.add(item)
    [visit(i) for i in ids]
    for key in [*data['reference_docs'],data['orchestrator_spec'],data['common_contract'],*data['protected_paths']]:
        if '*' not in key and not (ROOT/key).exists(): raise LoopError(f'missing required input: {key}')
    for stage in stages:
        for spec in stage['specs']:
            if not (ROOT/spec).exists(): raise LoopError(f"missing stage specification: {spec}")
def repo_dirty() -> list[str]:
    raw=git('status','--porcelain','-z',check=True).stdout
    paths=[]
    for entry in raw.split('\0'):
        if not entry: continue
        path=entry[3:]
        if path.startswith('.loop/'): continue
        paths.append(path)
    return paths
def checked_branch() -> str: return git('branch','--show-current').stdout.strip()
def resuming_stage_branch(branch: str, state: dict[str,Any], config: dict[str,Any]) -> bool:
    """Allow a recovered `run` only on the persisted, active lesson branch."""
    current=state.get('current_stage')
    if state.get('status')!='RUNNING' or state.get('current_branch')!=branch or not current:
        return False
    record=state.get('stages',{}).get(current,{})
    if record.get('status') not in {'RUNNING','VERIFYING'}:
        return False
    return any(stage['id']==current and stage['branch']==branch for stage in config['stages'])
def protected_hash(config: dict[str,Any]) -> str: return path_hash(config['protected_paths'])
def stage_hashes(config: dict[str,Any], stage: dict[str,Any]) -> dict[str,str]:
    inputs=[*config['reference_docs'],config['common_contract'],config['orchestrator_spec'],*stage['specs']]
    return {'spec_hash':path_hash(inputs),'output_hash':path_hash(stage['fingerprint_paths']),'judge_hash':sha_bytes(json.dumps(stage['judge'],sort_keys=True).encode()),'protected_hash':protected_hash(config)}
def load_state(config: dict[str,Any]) -> dict[str,Any]:
    for candidate in (STATE,BACKUP):
        if candidate.exists():
            try:
                state=read_json(candidate)
                if state.get('schema_version')==1: return state
            except Exception: pass
    return {'schema_version':1,'run_id':dt.datetime.now().strftime('%Y%m%d-%H%M%S-')+uuid.uuid4().hex[:4],'status':'RUNNING','source_root':str(ROOT),'base_branch':config['git']['base_branch'],'current_branch':checked_branch(),'master_head':git('rev-parse','HEAD').stdout.strip(),'current_stage':None,'started_at':now(),'updated_at':now(),'stages':{}}
def save(state: dict[str,Any]) -> None: state['updated_at']=now(); atomic_write(STATE,state)
def matching(path: str, patterns: list[str]) -> bool: return any(fnmatch.fnmatchcase(path,p) for p in patterns)
def changed_paths() -> list[str]: return repo_dirty()
def command_log(command: list[str], directory: pathlib.Path, timeout: int, env:dict[str,str]|None=None) -> tuple[bool,str]:
    start=time.monotonic()
    try: result=shell(command,timeout=timeout,check=False,env=env)
    except subprocess.TimeoutExpired: (directory/'judge.stdout.log').write_text(''); (directory/'judge.stderr.log').write_text('timeout'); return False, f'timeout after {timeout}s'
    out=(result.stdout or '')[-MAX_LOG:]; err=(result.stderr or '')[-MAX_LOG:]
    (directory/'judge.stdout.log').write_text(out); (directory/'judge.stderr.log').write_text(err)
    (directory/'judge-result.json').write_text(json.dumps({'command':command,'exit_code':result.returncode,'duration_seconds':round(time.monotonic()-start,3)},indent=2))
    return result.returncode==0, (err or out)[-4000:]
def redact(text: str) -> str:
    import re
    return re.sub(r'(?i)(password|token|secret|authorization|cookie)\s*[=:]\s*[^\s,]+',r'\1=[REDACTED]',text)

class Runner:
    def __init__(self, config: dict[str,Any]): self.config=config; self.state=load_state(config); self.stop_requested=False
    def validate(self) -> None:
        validate_config(self.config)
        branch=checked_branch()
        if branch not in ('master','lesson/46') and not resuming_stage_branch(branch,self.state,self.config):
            raise LoopError('validate requires master, lesson/46, or the persisted active lesson branch',4)
        dirty=repo_dirty()
        bootstrap_paths=['AGENTS.md','loop','loop.yaml','.gitignore','harness/']
        if dirty and not (branch=='lesson/46' and all(any(path==prefix or path.startswith(prefix) for prefix in bootstrap_paths) for path in dirty)):
            raise LoopError('working tree is not clean: '+', '.join(dirty),4)
        for program in ('git','python3','codex','java','redis-server','etcd'):
            if not shutil.which(program): raise LoopError(f'host executable not found: {program}',7)
        adapter=ROOT/self.config['agent']['adapter']; result=shell(['python3',str(adapter),'--probe'],timeout=20,check=False)
        if result.returncode: raise LoopError('Agent Adapter unavailable: '+result.stderr,5)
        for stage in self.config['stages']:
            if stage['lesson']==39: continue
            if not (ROOT/'mvnw').exists(): continue
            if not (ROOT/stage['judge']['command'][0]).exists(): raise LoopError(f"judge entry does not exist: {stage['judge']['command'][0]}")
        print('VALIDATE OK: lesson39 will create mvnw, pom.xml, and business modules before later judges use them.')
    def plan(self) -> None:
        self.validate()
        for stage in self.config['stages']:
            rec=self.state.get('stages',{}).get(stage['id'],{}); status=rec.get('status','PENDING')
            print(f"{stage['lesson']} {stage['branch']} {status} inputs={','.join(stage['specs'])} outputs={','.join(stage['write_paths'])} judge={' '.join(stage['judge']['command'])}")
    def valid_pass(self, stage:dict[str,Any]) -> bool:
        rec=self.state['stages'].get(stage['id']);
        if not rec or rec.get('status')!='PASSED': return False
        hashes=stage_hashes(self.config,stage)
        if any(rec.get(k)!=v for k,v in hashes.items()): return False
        if not rec.get('lesson_commit') or not rec.get('merge_commit'): return False
        return git('merge-base','--is-ancestor',rec['merge_commit'],'master',check=False).returncode==0
    def invalidate_if_needed(self) -> None:
        invalid=False
        for stage in self.config['stages']:
            rec=self.state['stages'].get(stage['id'])
            if invalid or (rec and rec.get('status')=='PASSED' and not self.valid_pass(stage)):
                if rec and rec.get('protected_hash') != protected_hash(self.config):
                    changed=git('diff','--name-only',f"{rec.get('merge_commit')}..master",'--',*self.config['protected_paths'],check=False).stdout.splitlines()
                    if changed: raise LoopError(f"ENVIRONMENT_ERROR protected input changed for {stage['id']}: {', '.join(changed)}",7)
                    rec['protected_hash']=protected_hash(self.config)
                    rec['fingerprint_migration']='runtime-only protected fingerprint migration'
                    if self.valid_pass(stage):
                        self.state['stages'][stage['id']]=rec
                        continue
                self.state['stages'][stage['id']]={'lesson':stage['lesson'],'branch':stage['branch'],'status':'PENDING','invalidation_reason':'input/output fingerprint changed'}; invalid=True
        save(self.state)
    def acquire(self):
        LOOP_DIR.mkdir(exist_ok=True); self.lock_handle=open(LOCK,'a+')
        try: fcntl.flock(self.lock_handle,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:
            self.lock_handle.close()
            del self.lock_handle
            raise LoopError('another Runner already holds .loop/run.lock',4)
        self.lock_handle.seek(0); self.lock_handle.truncate(); self.lock_handle.write(json.dumps({'pid':os.getpid(),'run_id':self.state['run_id'],'started_at':now()})); self.lock_handle.flush()
    def release(self):
        if hasattr(self,'lock_handle'): fcntl.flock(self.lock_handle,fcntl.LOCK_UN); self.lock_handle.close()
    def prepare_branch(self, stage:dict[str,Any]):
        if repo_dirty(): raise LoopError('working tree contains external changes: '+', '.join(repo_dirty()),4)
        git('switch','master');
        exists=git('show-ref','--verify','--quiet',f"refs/heads/{stage['branch']}",check=False).returncode==0
        if exists: git('switch',stage['branch'])
        else: git('switch','-c',stage['branch'],'master')
        self.state['current_branch']=stage['branch']; self.state['current_stage']=stage['id']; save(self.state)
    def context(self, stage:dict[str,Any], attempt:int, folder:pathlib.Path) -> pathlib.Path:
        upstream=[]
        for dep in stage['depends_on']:
            handoff=self.state['stages'].get(dep,{}).get('handoff')
            if handoff: upstream.append(handoff)
        inputs=[*self.config['reference_docs'],self.config['common_contract'],self.config['orchestrator_spec'],*stage['specs'],*upstream]
        manifest={'run_id':self.state['run_id'],'stage':stage['id'],'attempt':attempt,'files':[{'path':x,'sha256':path_hash([x])} for x in inputs if (ROOT/x).exists()]}
        (folder/'context-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
        previous=self.state['stages'].get(stage['id'],{}).get('last_failure','none')
        template=(ROOT/'harness/loop/prompts/stage.md').read_text()
        prompt=template.format(lesson=stage['lesson'],branch=stage['branch'],attempt=attempt,stage_goal=f"Implement only the deliverables of {stage['id']}.",spec_paths='\n'.join(f'- {x}' for x in inputs),upstream_handoffs='\n'.join(f'- {x}' for x in upstream) or '- none',write_paths=', '.join(stage['write_paths']),protected_paths=', '.join(self.config['protected_paths']),judge_command=' '.join(stage['judge']['command']),last_failure_or_none=previous)
        request=folder/'agent-request.md'; request.write_text(prompt); return request
    def check_boundary(self, stage:dict[str,Any]) -> tuple[bool,str]:
        bad=[]
        for path in changed_paths():
            if matching(path,self.config['protected_paths']): bad.append(path+' (protected)')
            elif not matching(path,stage['write_paths']): bad.append(path+' (outside write_paths)')
        return (not bad, ', '.join(bad))
    def services(self, stage:dict[str,Any], start:bool) -> None:
        services=stage.get('services',[])
        if not services: return
        scripts=self.config['runtime']['local_services']
        if start:
            result=shell([scripts['start'][0],*services],timeout=60,check=False)
            if result.returncode: raise LoopError('local dependency start failed: '+result.stderr,7)
            result=shell([*scripts['wait'],*services],timeout=45,check=False)
            if result.returncode: raise LoopError('local dependency wait failed: '+result.stderr,7)
        else: shell(scripts['stop'],timeout=30,check=False)
    def attempt(self,stage:dict[str,Any], number:int) -> tuple[bool,str]:
        folder=LOOP_DIR/'runs'/self.state['run_id']/stage['id']/f'attempt-{number:03d}'; folder.mkdir(parents=True,exist_ok=True)
        request=self.context(stage,number,folder)
        payload={'run_id':self.state['run_id'],'stage':stage['id'],'lesson':stage['lesson'],'branch':stage['branch'],'attempt':number,'workspace':str(ROOT),'prompt_file':str(request),'context_manifest':str(folder/'context-manifest.json'),'allowed_write_paths':stage['write_paths'],'timeout_seconds':self.config['agent']['timeout_seconds'],'environment_allowlist':self.config['agent']['environment_allowlist']}
        # The adapter receives a structured request; it sends only the stage prompt to Codex stdin.
        result=subprocess.run(['python3',str(ROOT/self.config['agent']['adapter'])],input=json.dumps(payload),text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,cwd=ROOT,timeout=self.config['agent']['timeout_seconds']+30)
        try: agent=json.loads(result.stdout.splitlines()[-1])
        except Exception: agent={'status':'failed','summary':redact(result.stderr or result.stdout)}
        (folder/'agent-result.json').write_text(json.dumps(agent,ensure_ascii=False,indent=2)); (folder/'agent-output.log').write_text(agent.get('raw_output',''))
        (folder/'changed-files.txt').write_text('\n'.join(changed_paths())+'\n')
        ok,boundary=self.check_boundary(stage)
        if not ok: return False, 'boundary violation: '+boundary
        if agent.get('status')!='completed': return False, 'agent failure: '+agent.get('summary','unknown')
        try:
            self.services(stage,True)
            env={}
            env_file=ROOT/self.config['runtime']['local_services']['env_file']
            if env_file.exists():
                for line in env_file.read_text().splitlines():
                    if '=' in line:
                        key,value=line.split('=',1); env[key]=value.strip("'")
            return command_log(stage['judge']['command'],folder,stage['judge'].get('timeout_seconds',self.config['defaults']['judge_timeout_seconds']),env)
        finally: self.services(stage,False)
    def pass_stage(self,stage:dict[str,Any],attempt:int) -> None:
        if not changed_paths(): raise LoopError(f"{stage['id']} passed without any staged implementation changes",6)
        git('add','--',*stage['write_paths']); git('commit','-m',f"lesson {stage['lesson']}: implement course deliverables")
        lesson_commit=git('rev-parse','HEAD').stdout.strip(); git('switch','master'); git('merge','--no-ff',stage['branch'],'-m',f"merge lesson {stage['lesson']}")
        merge_commit=git('rev-parse','HEAD').stdout.strip(); handoff=LOOP_DIR/'runs'/self.state['run_id']/stage['id']/'handoff.md'; handoff.parent.mkdir(parents=True,exist_ok=True); handoff.write_text(f"# Lesson {stage['lesson']} handoff\n\nlesson commit: {lesson_commit}\nmerge commit: {merge_commit}\nattempts: {attempt}\n")
        rec={'lesson':stage['lesson'],'branch':stage['branch'],'status':'PASSED','attempts':attempt,'lesson_commit':lesson_commit,'merge_commit':merge_commit,'passed_at':now(),'handoff':str(handoff.relative_to(ROOT)),**stage_hashes(self.config,stage)}
        self.state['stages'][stage['id']]=rec; self.state['current_branch']='master'; self.state['current_stage']=None; self.state['master_head']=merge_commit; save(self.state); print(f"PASS {stage['id']} branch={stage['branch']} merged=master attempt={attempt}")
    def final(self) -> bool:
        folder=LOOP_DIR/'runs'/self.state['run_id']; folder.mkdir(parents=True,exist_ok=True); results=[]; failure=None
        for judge in self.config['final_judges']:
            if failure and not judge.get('always_run'): continue
            judge_dir=folder/'final'/judge['id']; judge_dir.mkdir(parents=True,exist_ok=True)
            ok,detail=command_log(judge['command'],judge_dir,judge.get('timeout_seconds',1200))
            results.append({'id':judge['id'],'passed':ok,'detail':detail})
            if not ok and not judge.get('always_run') and failure is None: failure=judge['id']
        report=folder/'final-report.md'; lines=['# When Loop final report','',f"run_id: {self.state['run_id']}",'', '## Lessons']
        for stage in self.config['stages']:
            record=self.state['stages'].get(stage['id'],{}); lines.append(f"- {stage['id']}: {record.get('status','PENDING')}; lesson={record.get('lesson_commit','-')}; merge={record.get('merge_commit','-')}; attempts={record.get('attempts',0)}")
        lines+=['','## Final judges']+[f"- {x['id']}: {'PASS' if x['passed'] else 'FAIL'}" for x in results]
        report.write_text('\n'.join(lines)+'\n')
        if failure: return False
        self.state['status']='COMPLETE'; self.state['current_branch']='master'; self.state['current_stage']=None; save(self.state); print(f"LOOP COMPLETE\nrun_id: {self.state['run_id']}\nlessons: 7/7 passed\nfinal_judges: {sum(x['passed'] for x in results)}/{len(results)} passed\nreport: {report.relative_to(ROOT)}"); return True
    def run(self) -> int:
        self.validate(); self.acquire()
        try:
            self.invalidate_if_needed()
            for stage in self.config['stages']:
                if self.valid_pass(stage): print(f"SKIPPED(PASSED) {stage['id']}"); continue
                self.prepare_branch(stage); attempts=self.state['stages'].get(stage['id'],{}).get('attempts',0)
                while True:
                    attempts+=1; self.state['stages'][stage['id']]={'lesson':stage['lesson'],'branch':stage['branch'],'status':'RUNNING','attempts':attempts}; save(self.state)
                    ok,detail=self.attempt(stage,attempts)
                    if ok: self.pass_stage(stage,attempts); break
                    self.state['stages'][stage['id']].update({'status':'VERIFYING','last_failure':redact(detail),'last_failure_fingerprint':sha_bytes(redact(detail).encode()),'last_attempt':f".loop/runs/{self.state['run_id']}/{stage['id']}/attempt-{attempts:03d}"}); save(self.state); print(f"RETRY {stage['id']} attempt={attempts}: {redact(detail)[:240]}")
                    if attempts % self.config['defaults']['attempts_per_batch']==0: self.state['stages'][stage['id']]['replan_batches']=attempts//self.config['defaults']['attempts_per_batch']; save(self.state)
            git('switch','master')
            return 0 if self.final() else 6
        finally: self.release()
    def status(self,json_output:bool=False):
        if json_output: print(json.dumps(self.state,ensure_ascii=False,indent=2)); return
        print(f"run_id={self.state['run_id']} status={self.state['status']} current={self.state.get('current_stage')}")
        for stage in self.config['stages']:
            rec=self.state.get('stages',{}).get(stage['id'],{}); print(f"{stage['id']} {rec.get('status','PENDING')} branch={stage['branch']} attempts={rec.get('attempts',0)}")
    def logs(self,stage:str,attempt:int|None):
        rec=self.state.get('stages',{}).get(stage,{})
        target=LOOP_DIR/'runs'/self.state['run_id']/stage/(f'attempt-{attempt:03d}' if attempt else pathlib.Path(rec.get('last_attempt','x')).name)
        for name in ('agent-result.json','judge.stderr.log','judge.stdout.log','attempt-summary.md'):
            path=target/name
            if path.exists(): print(f'## {name}\n{path.read_text()[-8000:]}')
    def invalidate(self,stage_id:str,downstream:bool):
        found=False
        for stage in self.config['stages']:
            if stage['id']==stage_id: found=True
            if found:
                if stage['id']!=stage_id and not downstream: break
                self.state['stages'][stage['id']]={'lesson':stage['lesson'],'branch':stage['branch'],'status':'PENDING','invalidation_reason':'manual invalidation'}
        if not found: raise LoopError('unknown stage '+stage_id)
        save(self.state); print('invalidated '+stage_id+(' and downstream' if downstream else ''))
    def report(self):
        folder=LOOP_DIR/'runs'/self.state['run_id']; report=folder/'final-report.md'
        if not report.exists(): self.final()
        print(report.relative_to(ROOT))

def main() -> int:
    parser=argparse.ArgumentParser(); parser.add_argument('--config',default='loop.yaml'); sub=parser.add_subparsers(dest='command',required=True); sub.add_parser('validate'); sub.add_parser('plan'); sub.add_parser('run'); pstatus=sub.add_parser('status'); pstatus.add_argument('--json',action='store_true'); plog=sub.add_parser('logs'); plog.add_argument('--stage',required=True); plog.add_argument('--attempt',type=int); pinv=sub.add_parser('invalidate'); pinv.add_argument('--stage',required=True); pinv.add_argument('--downstream',action='store_true'); sub.add_parser('report'); args=parser.parse_args()
    try:
        runner=Runner(load_config(args.config))
        if args.command=='validate': runner.validate(); return 0
        if args.command=='plan': runner.plan(); return 0
        if args.command=='run': return runner.run()
        if args.command=='status': runner.status(args.json); return 0
        if args.command=='logs': runner.logs(args.stage,args.attempt); return 0
        if args.command=='invalidate': runner.invalidate(args.stage,args.downstream); return 0
        if args.command=='report': runner.report(); return 0
    except LoopError as exc: print(str(exc),file=sys.stderr); return exc.code
    except KeyboardInterrupt: print('INTERRUPTED',file=sys.stderr); return 130
    except Exception as exc: print('RUNNER_INTERNAL_ERROR '+str(exc),file=sys.stderr); return 10
if __name__=='__main__': raise SystemExit(main())
