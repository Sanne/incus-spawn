#!/usr/bin/env python3
"""Reproduce Git's pre-2.55 dissociation/commit-graph failure in disposable repositories."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

rows = []
for binary in sys.argv[1:] or ["git"]:
    binary = str(Path(shutil.which(binary) or binary).resolve())
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null',
               GIT_AUTHOR_NAME='Research', GIT_AUTHOR_EMAIL='research@example.invalid',
               GIT_COMMITTER_NAME='Research', GIT_COMMITTER_EMAIL='research@example.invalid',
               GIT_TERMINAL_PROMPT='0')
    if (Path(binary).parent/'git-upload-pack').exists():
        env['GIT_EXEC_PATH'] = str(Path(binary).parent)
        env['PATH'] = str(Path(binary).parent) + os.pathsep + env['PATH']
    def git(*args, check=True):
        p = subprocess.run([binary, '-c', 'gc.auto=0', '-c', 'maintenance.auto=false',
                            '-c', 'core.hooksPath=/dev/null', *map(str, args)], env=env,
                           capture_output=True, text=True)
        if check and p.returncode:
            raise RuntimeError(p.stderr)
        return p
    version = git('--version').stdout.strip()
    with tempfile.TemporaryDirectory(prefix='isx-dissociate-graph-') as tmp:
        root = Path(tmp)
        orig, graph = root/'orig', root/'graph.git'
        git('init', '--template=', '--initial-branch=main', orig)
        (orig/'file').write_text('public content\n')
        git('-C', orig, 'add', 'file')
        git('-C', orig, 'commit', '-qm', 'initial')
        git('clone', '--template=', '--bare', orig, graph)
        git('-C', graph, 'commit-graph', 'write', '--reachable')
        for mode in ('normal', 'disable-commit-graph', 'no-checkout', 'bare', 'deferred'):
            dst = root/mode
            args = ['-c', 'core.commitGraph=false'] if mode == 'disable-commit-graph' else []
            args += ['clone', '--template=', '--no-local', '--reference', str(graph)]
            if mode != 'deferred': args += ['--dissociate']
            if mode in ('no-checkout', 'bare'): args += ['--'+mode]
            p = git(*args, orig, dst, check=False)
            row = dict(git=version, mode=mode, exit=p.returncode, stderr=p.stderr)
            if p.returncode == 0:
                if mode == 'deferred':
                    git('-C', dst, 'repack', '-a', '-d')
                    (dst/'.git/objects/info/alternates').unlink()
                hidden = root/'hidden-reference'
                graph.rename(hidden)
                try:
                    git('-C', dst, 'fsck', '--full', '--no-dangling')
                    if mode != 'bare':
                        git('-C', dst, 'reset', '--hard', 'HEAD')
                        assert (dst/'file').read_text() == 'public content\n'
                    row['independent_fsck_and_checkout'] = True
                finally:
                    hidden.rename(graph)
            rows.append(row)
print(json.dumps(rows, indent=2))
