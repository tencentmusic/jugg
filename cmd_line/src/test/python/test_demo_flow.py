"""Exercise public demo scripts across processes, with a real Git repo and HTTP store.

The command-line fixture replaces Android compilation only; policy, scenario edits,
Git commits, baseline copies and result publication all run as shipped.
"""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile
from http.server import HTTPServer

DEMO = Path(__file__).resolve().parents[2] / 'demo'
FIXTURE = '''#!/usr/bin/env python3
import json
from pathlib import Path
import subprocess
import sys
import zipfile

params = dict(arg.split('=', 1) for arg in sys.argv[1:])
print('fixture command stdout')
print('fixture command stderr', file=sys.stderr)
command = params['cmd']
if (Path.cwd() / '.git/fail-incremental').exists() and command == 'buildIncrementalApk':
    sys.exit(7)
if command == 'findNearestBaseCommit':
    candidates = json.loads(Path(params['candidateCommitsFile']).read_text())
    history = subprocess.check_output(['git', 'rev-list', '--first-parent', params['targetCommit']], text=True).splitlines()
    match = next((commit for commit in history if commit in candidates), None)
    Path(params['output']).write_text(json.dumps({'matchedCommit': match}))
elif command == 'getChangedFiles':
    paths = subprocess.check_output(['git', 'diff', '--name-only', params['baseCommit'], params['targetCommit']], text=True).splitlines()
    types = {'.gradle': 'BuildFile', '.java': 'Java', '.kt': 'Kotlin', '.xml': 'Resource'}
    files = [{'file': str(Path.cwd() / path), 'type': types.get(Path(path).suffix, 'Unknown'), 'module': 'app'} for path in paths]
    Path(params['output']).write_text(json.dumps({'files': files}))
else:
    output = Path(params['outputApkDir']) if command != 'buildGradleBase' else Path(params['baseBuildProjectDir']) / 'build/jugg/classpath/apk'
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output / 'app.apk', 'w') as archive:
        config = Path.cwd() / 'config.json'
        if config.exists():
            archive.writestr('assets/config.json', config.read_text())
    if command == 'buildGradleBase':
        root = Path(params['baseBuildProjectDir'])
        initialized = root / '.git/fixture-gradle-initialized'
        if not initialized.exists():
            state = root / 'build/app/retained-output'
            state.parent.mkdir(parents=True, exist_ok=True)
            state.write_text('prior Gradle output')
            initialized.touch()
        (root / 'build/jugg').mkdir(parents=True, exist_ok=True)
        (root / 'build/jugg/history').write_text('clean')
        mapping = root / 'build/app/outputs/mapping/release'
        mapping.mkdir(parents=True, exist_ok=True)
        (mapping / 'mapping.txt').write_text('mapping fixture')
        (mapping / 'usage.txt').write_text('usage fixture')
    else:
        if params['changedFiles']:
            Path(params['baseBuildJuggRootDir'], '.dirty').touch()
'''


class DemoFlowTest(unittest.TestCase):
    def test_continuous_flow_and_rerun_preserve_forward_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            demo = root / 'demo'
            for name in ('scripts', 'demo_flow'):
                shutil.copytree(DEMO / name, demo / name, ignore=shutil.ignore_patterns('__pycache__'))
            jars = demo / 'custom_compilers'
            jars.mkdir()
            for name in ('custom_compiler_instrument-1.0.jar', 'dependency.jar'):
                (jars / name).touch()
            self.write_project_archive(demo)
            cli = root / 'cmd_line'
            cli.write_text(FIXTURE.replace('#!/usr/bin/env python3', '#!' + sys.executable, 1))
            cli.chmod(0o755)
            spec = importlib.util.spec_from_file_location('store', demo / 'scripts/base_build_server.py')
            store = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(store)
            server = HTTPServer(('127.0.0.1', 0), store.Handler)
            server.data_file = str(root / 'base-builds.json')
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                for variant in ('debug', 'release'):
                    with self.subTest(variant=variant):
                        self.check_flow(demo, cli, root, server, variant, quite=False)
                        self.check_flow(demo, cli, root, server, variant, quite=True)
                self.check_jugg_failure(demo, cli, root, server)
                self.check_quite_failure(demo, cli, root, server)
            finally:
                server.shutdown()
                thread.join()
                server.server_close()

    def write_project_archive(self, demo):
        files = {
            '.gitignore': '**/build/\n',
            'gradlew': '#!/bin/sh\nif [ "$1" = "clean" ]; then\n  rm -rf build\nfi\n',
            'app/build.gradle': 'plugins {}\n',
            'app/src/main/java/com/example/myapplication/MainActivity.kt':
                'class MainActivity { fun onCreate() {\n super.onCreate(savedInstanceState)\n} }\n',
            'app/src/main/res/layout/activity_main.xml': '<TextView android:text="before" />',
        }
        with zipfile.ZipFile(demo / 'demo_project.zip', 'w') as archive:
            for name, content in files.items():
                archive.writestr('demo_project/' + name, content)

    def check_flow(self, demo, cli, root, server, variant, quite):
        work = root / variant
        work.mkdir(exist_ok=True)
        stale_file = work / 'previous-run.txt'
        stale_file.write_text('discard this entire work directory before starting')
        result = subprocess.run([
            sys.executable, str(demo / 'demo_flow/run.py'),
            '--work-dir', str(work), '--scenario', variant, '--cmd-line', str(cli),
            '--server', f'http://127.0.0.1:{server.server_port}', '--project-id', variant,
        ] + (['--quite'] if quite else []), text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for message in ('开始演示', '清理工作目录', '准备演示工程', '首次构建',
                        '开发修改', '提交开发变更', '再次构建', '构建完成', '演示完成'):
            self.assertIn(message, result.stdout)
        if quite:
            self.assertNotIn('fixture command', result.stdout + result.stderr)
            self.assertNotIn('+ ', result.stdout)
            self.assertEqual(result.stderr, '')
        else:
            self.assertIn('fixture command stdout', result.stdout)
            self.assertIn('fixture command stderr', result.stderr)
        self.assertFalse(stale_file.exists())
        self.assertEqual(subprocess.check_output(
            ['git', 'rev-list', '--count', 'HEAD'], cwd=work / 'project', text=True).strip(), '7')
        document = json.loads((work / 'build-result.json').read_text())
        self.assertEqual(document['mode'], 'buildGradleBase')
        results = json.loads((work / 'flow-results.json').read_text())
        self.assertEqual([r['mode'] for r in results], ['buildGradleBase', 'buildIncrementalApk',
                         'buildGradleBase', 'buildGradleBase', 'reuseBaseApk', 'buildGradleBase'])
        commits = subprocess.check_output(['git', 'rev-list', '--reverse', 'HEAD'],
                                          cwd=work / 'project', text=True).splitlines()
        self.assertEqual([r['commit'] for r in results], commits[1:])
        self.assertEqual(result.stdout.count('清理工作目录：'), 1)
        self.assertEqual(result.stdout.count('首次构建：'), 1)
        self.assertIn('编排主动过滤 README：1', result.stdout)
        self.assertIn('未处理的 Unknown', result.stdout)
        self.assertIn('APK 的 assets/config.json 已更新', result.stdout)
        self.assertTrue((work / 'project/build/app/retained-output').is_file(),
                        'Gradle fallback removed prior module output')
        records = [record for record in json.loads(Path(server.data_file).read_text())
                   if record['metadata']['projectDir'] == str(work / 'project')
                   and record['metadata']['demoCheckoutId'] == (work / 'project/.git/jugg-demo-id').read_text()
                   and Path(record['juggRootPath']).is_dir()]
        self.assertEqual(len(records), 4)
        self.assertTrue(all(record['projectId'] == variant for record in records))
        for record in records:
            self.assertNotIn('apkPath', record)
            self.assertEqual(Path(record['juggRootPath']), root / 'base_build' / variant / record['id'])
            self.assertTrue((Path(record['juggRootPath']) / 'classpath/apk/app.apk').is_file())
        self.assertFalse(list(work.glob('runs/*/baseline')))
        self.assertEqual(len(list(work.glob('runs/*/apk'))), 2)
        self.assertTrue(all(not (Path(record['juggRootPath']) / '.dirty').exists() for record in records))
        self.assertEqual(len(list(work.glob('runs/*/jugg/.dirty'))), 1)
        changes = [json.loads((Path(r['runDir']) / 'changed-files.json').read_text())['files']
                   for r in results[1:]]
        self.assertEqual([len(files) for files in changes], [2, 3, 101, 1, 2])
        self.assertEqual(changes[-2][0]['type'], 'Unknown')
        self.assertTrue((Path(document['apkDir']) / 'app.apk').is_file())
        with zipfile.ZipFile(Path(document['apkDir']) / 'app.apk') as archive:
            self.assertEqual(json.loads(archive.read('assets/config.json')), {'message': 'updated config'})
        if variant == 'release':
            self.assertEqual(len(list(work.glob('runs/*/mapping.txt'))), 4)

    def check_jugg_failure(self, demo, cli, root, server):
        work = root / 'debug'
        project = work / 'project'
        source = project / 'app/src/main/java/com/example/myapplication/MainActivity.kt'
        source.write_text(source.read_text() + '// Another committed source change\n')
        subprocess.run(['git', 'add', str(source)], cwd=project, check=True, capture_output=True)
        subprocess.run(['git', 'commit', '-m', 'change source before Jugg failure'], cwd=project,
                       check=True, capture_output=True)
        (project / '.git/fail-incremental').touch()
        result = subprocess.run([sys.executable, str(demo / 'scripts/jugg_build.py'),
                                 '--work-dir', str(work), '--cmd-line', str(cli), '--project-id', 'debug',
                                 '--server', f'http://127.0.0.1:{server.server_port}'],
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        document = json.loads((work / 'build-result.json').read_text())
        self.assertEqual(document['mode'], 'buildGradleBase')
        self.assertIn('Jugg CI failed', document['reason'])
        self.assertTrue((Path(document['apkDir']) / 'app.apk').is_file())

    def check_quite_failure(self, demo, cli, root, server):
        cli.write_text('#!' + sys.executable + '\nimport sys\n'
                       'print("fixture command stdout")\n'
                       'print("fixture command stderr", file=sys.stderr)\n'
                       'sys.exit(7)\n')
        result = subprocess.run([
            sys.executable, str(demo / 'demo_flow/run.py'), '--quite',
            '--work-dir', str(root / 'failed'), '--cmd-line', str(cli),
            '--server', f'http://127.0.0.1:{server.server_port}',
        ], text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('命令执行失败', result.stdout)
        self.assertNotIn('fixture command', result.stdout + result.stderr)
        self.assertNotIn('演示完成', result.stdout)


if __name__ == '__main__':
    unittest.main()
