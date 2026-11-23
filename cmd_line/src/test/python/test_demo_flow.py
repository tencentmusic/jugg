"""Exercise public demo scripts across processes, with a real Git repo and HTTP store.

The command-line fixture replaces Android compilation only; policy, scenario edits,
Git commits, ZIP transfer and result publication all run as shipped.
"""
import importlib.util
import json
from pathlib import Path
import shutil
import socket
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
    partial = Path(params['outputApkDir'])
    partial.mkdir(parents=True)
    (partial / 'partial.apk').write_bytes(b'partial incremental output')
    sys.exit(7)
if command == 'findNearestBaseCommit':
    candidates = json.loads(Path(params['candidateCommitsFile']).read_text())
    history = subprocess.check_output(['git', '-C', params['projectDir'], 'rev-list', '--first-parent', params['targetCommit']], text=True).splitlines()
    match = next((commit for commit in history if commit in candidates), None)
    Path(params['output']).write_text(json.dumps({'matchedCommit': match}))
elif command == 'getChangedFiles':
    paths = subprocess.check_output(['git', 'diff', '--name-only', params['baseCommit'], params['targetCommit']], text=True).splitlines()
    types = {'.gradle': 'BuildFile', '.java': 'Java', '.kt': 'Kotlin', '.xml': 'Resource'}
    files = [{'file': str(Path.cwd() / path), 'type': types.get(Path(path).suffix, 'Unknown'), 'module': 'app', 'gitRoot': str(Path.cwd())} for path in paths]
    Path(params['output']).write_text(json.dumps({'files': files, 'repositories': [
        {'gitRoot': str(Path.cwd()), 'baseCommit': params['baseCommit'], 'targetCommit': params['targetCommit']}]}))
else:
    output = Path(params['outputApkDir'])
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
        history = root / 'build/jugg/database/deploy_history.db/deploy_history.json'
        history.parent.mkdir(parents=True, exist_ok=True)
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
        history.write_text(json.dumps({'fullCompileGitCommitHash': commit}))
        baseline_apk = root / 'build/jugg/classpath/apk'
        baseline_apk.mkdir(parents=True, exist_ok=True)
        (baseline_apk / 'app.apk').write_bytes((output / 'app.apk').read_bytes())
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
            for name in ('jugg_ci', 'demo_flow'):
                shutil.copytree(DEMO / name, demo / name, ignore=shutil.ignore_patterns('__pycache__'))
            jars = demo / 'custom_compilers'
            jars.mkdir()
            for name in ('custom_compiler_instrument-1.0.jar', 'dependency.jar'):
                (jars / name).touch()
            self.write_project_archive(demo)
            cli = root / 'cmd_line'
            cli.write_text(FIXTURE.replace('#!/usr/bin/env python3', '#!' + sys.executable, 1))
            cli.chmod(0o755)
            spec = importlib.util.spec_from_file_location('store', demo / 'jugg_ci/base_build_server.py')
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
                        self.check_flow(demo, cli, root, server if variant == 'debug' else None, variant, quite=True)
                self.check_dry_run(demo, cli, root, server)
                self.check_jugg_failure(demo, cli, root, server)
                self.check_quite_failure(demo, cli, root)
            finally:
                server.shutdown()
                thread.join()
                server.server_close()

    def write_project_archive(self, demo):
        files = {
            'gradlew': '#!/bin/sh\nif [ "$1" = "clean" ]; then\n  rm -rf build\nfi\n',
            'app/build.gradle': 'plugins {}\n',
            'app/src/main/java/com/example/myapplication/MainActivity.kt':
                'class MainActivity { fun onCreate() {\n super.onCreate(savedInstanceState)\n} }\n',
            'app/src/main/res/layout/activity_main.xml': '<TextView android:text="before" />',
            'app/src/main/java/com/sickworm/jugg/demo/testcase/annotation/kotlin/KtARouterActivity.kt':
                '@Route(path = "/app/activity_kt")\nclass KtARouterActivity\n',
        }
        with zipfile.ZipFile(demo / 'demo_project.zip', 'w') as archive:
            for name, content in files.items():
                archive.writestr('demo_project/' + name, content)

    def check_flow(self, demo, cli, root, server, variant, quite):
        work = root / variant if server else demo / 'build/ci-demo'
        work.mkdir(parents=True, exist_ok=True)
        stale_file = work / 'previous-run.txt'
        stale_file.write_text('discard this entire work directory before starting')
        if server is None:
            (work / 'server').mkdir()
            (work / 'server/base-builds.json').write_text('obsolete index')
        server_args = ['--work-dir', str(work), '--server', f'http://127.0.0.1:{server.server_port}'] if server else []
        result = subprocess.run([
            sys.executable, str(demo / 'demo_flow/run.py'),
            '--scenario', variant, '--cmd-line', str(cli),
            '--project-id', variant, *server_args,
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
            self.assertIn('[07] fixture command stdout', result.stdout)
            self.assertIn('[07] fixture command stderr', result.stdout)
        self.assertFalse(stale_file.exists())
        self.assertEqual(subprocess.check_output(
            ['git', 'rev-list', '--count', 'HEAD'], cwd=work / 'project', text=True).strip(), '8')
        document = json.loads((work / 'build-result.json').read_text())
        self.assertEqual(document['mode'], 'buildGradleBase')
        results = json.loads((work / 'flow-results.json').read_text())
        self.assertEqual([r['mode'] for r in results], ['buildGradleBase', 'buildIncrementalApk',
                         'buildGradleBase', 'buildGradleBase', 'reuseBaseApk', 'buildIncrementalApk', 'buildGradleBase'])
        build_log = (Path(results[1]['runDir']) / 'buildIncrementalApk.log').read_text()
        self.assertIn('fixture command stdout', build_log)
        self.assertIn('fixture command stderr', build_log)
        commits = subprocess.check_output(['git', 'rev-list', '--reverse', 'HEAD'],
                                          cwd=work / 'project', text=True).splitlines()
        self.assertEqual([r['commit'] for r in results], commits[1:])
        self.assertEqual(result.stdout.count('清理工作目录：'), 1)
        self.assertEqual(result.stdout.count('首次构建：'), 1)
        self.assertIn('编排主动过滤 README：1', result.stdout)
        self.assertIn(f'存在需要 Gradle 处理的构建输入：{work / "project/config.json"}', result.stdout)
        self.assertIn('APK 的 assets/config.json 已更新', result.stdout)
        self.assertTrue((work / 'project/build/app/retained-output').is_file(),
                        'Gradle fallback removed prior module output')
        index = Path(server.data_file) if server else work / 'server/base-builds.json'
        if server is None:
            self.assert_server_closed(work, result.stdout)
        records = [record for record in json.loads(index.read_text())
                   if record['metadata']['projectDir'] == str(work / 'project')
                   and record['metadata']['demoCheckoutId'] == (work / 'project/.git/jugg-demo-id').read_text()
                   and record['artifactId']]
        self.assertEqual(len(records), 4)
        self.assertTrue(all(record['projectId'] == variant for record in records))
        for record in records:
            self.assertNotIn('apkPath', record)
            self.assertNotIn('juggRootPath', record)
            with zipfile.ZipFile(index.parent / 'artifacts' / (record['artifactId'] + '.zip')) as archive:
                self.assertIn('jugg/classpath/apk/app.apk', archive.namelist())
                self.assertNotIn('jugg/.dirty', archive.namelist())
        self.assertFalse(list(work.glob('runs/*/baseline')))
        self.assertEqual(len(list(work.glob('runs/*/candidates/*/jugg/.dirty'))), 2)
        for index, count in ((1, 2), (4, 1), (5, 2)):
            changes = Path(results[index]['runDir']) / 'changed-files.json'
            self.assertEqual(len(json.loads(changes.read_text())['files']), count)
        route_patches = Path(results[5]['runDir']).glob('candidates/*/source-diff-*.patch')
        self.assertTrue(any('+@Route(path = "/app/activity_ci_demo")' in p.read_text() for p in route_patches))
        self.assertTrue((Path(document['apkDir']) / 'app.apk').is_file())
        with zipfile.ZipFile(Path(document['apkDir']) / 'app.apk') as archive:
            self.assertEqual(json.loads(archive.read('assets/config.json')), {'message': 'updated config'})
        if variant == 'release':
            self.assertEqual(len(list(work.glob('runs/*/mapping.txt'))), 4)

    def check_dry_run(self, demo, cli, root, server):
        work = root / 'debug'
        result_file = work / 'build-result.json'
        before = result_file.read_bytes()
        project = work / 'project'
        subprocess.run(['git', 'rm', 'README.md'], cwd=project, check=True, capture_output=True)
        subprocess.run(['git', 'commit', '-m', 'remove ignored README'], cwd=project,
                       check=True, capture_output=True)
        index_file = Path(server.data_file)
        records = json.loads(index_file.read_text())
        for record in records:
            if record['projectId'] == 'debug':
                record['compileCommand'] = 'legacy metadata'
        index_file.write_text(json.dumps(records))
        index = index_file.read_bytes()
        result = subprocess.run([sys.executable, str(demo / 'jugg_ci/build.py'), '--dry-run',
                                 '--work-dir', str(work), '--cmd-line', str(cli), '--project-id', 'debug',
                                 '--server', f'http://127.0.0.1:{server.server_port}'],
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        plan = json.loads((work / 'dry-run-result.json').read_text())
        self.assertEqual(plan['plannedMode'], 'reuseBaseApk')
        self.assertTrue(plan['dryRun'])
        self.assertEqual(plan['changedFileCount'], 0)
        self.assertEqual(plan['baseCommit'], json.loads(before)['commit'])
        self.assertTrue(plan['baseId'])
        self.assertEqual(len(plan['repositories']), 1)
        self.assertFalse((Path(plan['runDir']) / 'apk').exists())
        self.assertEqual(result_file.read_bytes(), before)
        self.assertEqual(Path(server.data_file).read_bytes(), index)

    def check_jugg_failure(self, demo, cli, root, server):
        work = root / 'debug'
        project = work / 'project'
        source = project / 'app/src/main/java/com/example/myapplication/MainActivity.kt'
        source.write_text(source.read_text() + '// Another committed source change\n')
        subprocess.run(['git', 'add', str(source)], cwd=project, check=True, capture_output=True)
        subprocess.run(['git', 'commit', '-m', 'change source before Jugg failure'], cwd=project,
                       check=True, capture_output=True)
        (project / '.git/fail-incremental').touch()
        result = subprocess.run([sys.executable, str(demo / 'jugg_ci/build.py'),
                                 '--work-dir', str(work), '--cmd-line', str(cli), '--project-id', 'debug',
                                 '--server', f'http://127.0.0.1:{server.server_port}'],
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        document = json.loads((work / 'build-result.json').read_text())
        self.assertEqual(document['mode'], 'buildGradleBase')
        self.assertIn('Jugg 基线复用失败', document['reason'])
        self.assertEqual([Path(path).name for path in document['apks']], ['app.apk'])
        self.assertTrue((Path(document['apkDir']) / 'app.apk').is_file())

    def check_quite_failure(self, demo, cli, root):
        cli.write_text('#!' + sys.executable + '\nimport sys\n'
                       'print("fixture command stdout")\n'
                       'print("fixture command stderr", file=sys.stderr)\n'
                       'sys.exit(7)\n')
        result = subprocess.run([
            sys.executable, str(demo / 'demo_flow/run.py'), '--quite',
            '--work-dir', str(root / 'failed'), '--cmd-line', str(cli),
        ], text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('命令执行失败', result.stdout)
        self.assertNotIn('fixture command', result.stdout + result.stderr)
        self.assertNotIn('演示完成', result.stdout)
        self.assert_server_closed(root / 'failed', result.stdout)

    def assert_server_closed(self, work, output):
        self.assertIn('启动本地基线服务：', output)
        self.assertIn('本地基线服务已关闭', output)
        startup = (work / 'server/server.log').read_text().splitlines()[0]
        port = int(startup.rsplit(':', 1)[1])
        with self.assertRaises(ConnectionRefusedError):
            socket.create_connection(('127.0.0.1', port), timeout=2)


if __name__ == '__main__':
    unittest.main()
