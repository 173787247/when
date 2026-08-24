#!/usr/bin/env python3
"""External structural contract checks. This file intentionally has no When implementation."""
from __future__ import annotations
import argparse, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DATA = json.loads((ROOT / "harness/contracts/contract-data.json").read_text())

def files_under(names):
    result=[]
    for name in names:
        p=ROOT/name
        if p.is_file(): result.append(p)
        elif p.is_dir(): result.extend(x for x in p.rglob('*') if x.is_file())
    return result

def require(condition, message, errors):
    if not condition: errors.append(message)

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--stage', required=True); parser.add_argument('--final', action='store_true'); args=parser.parse_args()
    errors=[]; expected=DATA['stages'].get(args.stage)
    require(expected is not None, f'unknown contract stage {args.stage}', errors)
    if expected:
        for name in expected: require((ROOT/name).exists(), f'missing required stage output: {name}', errors)
    if args.stage == 'lesson39':
        require((ROOT/'when-common/src/main/proto/when-common.proto').exists(), 'missing common proto', errors)
        text='\n'.join(p.read_text(errors='ignore') for p in files_under(['when-common']))
        for token in ('MessageStatus','StoragePlugin','TimeWheel','SinkConfig','DueMessageHandler'):
            require(token in text, f'common contract missing {token}', errors)
    if args.stage == 'lesson40':
        proto=ROOT/'when-api/src/main/proto/when-api.proto'; text=proto.read_text(errors='ignore') if proto.exists() else ''
        for token in ('DelayMessageService','rpc Submit','rpc Query','rpc Cancel','import "when-common.proto"'):
            require(token in text, f'API proto missing {token}', errors)
    if args.stage == 'lesson41':
        text='\n'.join(p.read_text(errors='ignore') for p in files_under(['when-storage-redis']))
        require('RedisStoragePlugin' in text, 'Redis StoragePlugin implementation not found', errors)
        require('when:msg:' in text and 'when:tw:' in text, 'required Redis key prefixes not found', errors)
        require('ZADD' not in text.upper(), 'forbidden scheduling ZSet operation found', errors)
    if args.stage in ('lesson42','lesson43'):
        text='\n'.join(p.read_text(errors='ignore') for p in files_under(['when-cluster']))
        for token in ('/when/nodes/', '/when/controller', '/when/timewheels/'):
            require(token in text, f'ETCD key contract missing {token}', errors)
        if args.stage == 'lesson43': require('ClusterMembership' in text and 'EtcdClusterView' in text, 'membership/cluster view contract missing', errors)
    if args.stage == 'lesson44':
        text='\n'.join(p.read_text(errors='ignore') for p in files_under(['when-timewheel']))
        require('HashedWheelTimer' in text and 'DueMessageHandler' in text, 'Netty timer/due-handler contract missing', errors)
    if args.stage == 'lesson45':
        text='\n'.join(p.read_text(errors='ignore') for p in files_under(['when-ingress-router','when-app','when-acceptance']))
        for token in ('Router','DelayMessageHandler','PENDING','CANCELLED'):
            require(token in text, f'ingress contract missing {token}', errors)
    scanned='\n'.join(p.read_text(errors='ignore') for p in files_under(['pom.xml','when-common','when-api','when-storage-redis','when-cluster','when-timewheel','when-ingress-router','when-app','when-acceptance']))
    for term in DATA['forbidden_terms']:
        require(term.lower() not in scanned.lower(), f'forbidden runtime reference: {term}', errors)
    if errors:
        print('CONTRACT FAILURE', file=sys.stderr)
        print('\n'.join(f'- {x}' for x in errors), file=sys.stderr)
        return 1
    print(f'contract checks passed: {args.stage}')
    return 0
if __name__ == '__main__': raise SystemExit(main())
