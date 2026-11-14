import importlib.util
import pathlib
import unittest

PATH = pathlib.Path(__file__).resolve().parents[2] / 'demo' / 'scripts' / 'jugg_build.py'

class DemoPolicyTest(unittest.TestCase):
    def test_incremental_file_limit_includes_100_but_rejects_101(self):
        spec = importlib.util.spec_from_file_location('demo', PATH)
        demo = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(demo)
        files = [{'type': 'Kotlin', 'file': __file__}] * 100
        self.assertTrue(demo.should_use_incremental(files, False))
        self.assertFalse(demo.should_use_incremental(files + files[:1], False))
        unknown = [{'type': 'Unknown', 'file': __file__}] * 101
        self.assertFalse(demo.should_use_incremental(files + unknown, False))

    def test_build_file_and_missing_inputs_reject_incremental(self):
        spec = importlib.util.spec_from_file_location('demo', PATH)
        demo = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(demo)
        self.assertFalse(demo.should_use_incremental([{'type': 'BuildFile', 'file': __file__}], False))
        self.assertFalse(demo.should_use_incremental([], False))
        self.assertFalse(demo.should_use_incremental([{'type': 'Kotlin', 'file': '/missing/input.kt'}], False))
        self.assertTrue(demo.should_use_incremental([{'type': 'Kotlin', 'file': __file__}], False))
        self.assertFalse(demo.should_use_incremental([{'type': 'Kotlin', 'file': __file__}], True))

    def test_two_builds_and_forced_fallback_preserve_clean_baseline(self):
        import json
        import shutil
        import tempfile
        from types import SimpleNamespace
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location('demo', PATH)
        demo = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(demo)
        with tempfile.TemporaryDirectory() as directory:
            work = pathlib.Path(directory)
            project = work / 'project'
            project.mkdir()
            source = project / 'Source.kt'
            source.write_text('class Source')
            readme = project / 'README.md'
            readme.write_text('Documentation')
            changes = []
            fail_command = None
            records, commands = [], []
            args = SimpleNamespace(scenario='debug', server='http://localhost', cmd_line='fixture',
                                   project_id='example')

            def storage(server, path, record=None, project_id='default'):
                if record:
                    destination = work / 'base_build' / project_id / record['id']
                    shutil.copytree(record['juggRootPath'], destination)
                    record = dict(record, juggRootPath=str(destination))
                    records.append(record)
                    return record
                return records

            def command(argv, cwd):
                nonlocal fail_command
                if argv[0] == 'git':
                    return 'target'
                if argv[0] == './gradlew':
                    return ''
                params = dict(arg.split('=', 1) for arg in argv[1:])
                cmd = params['cmd']
                commands.append(cmd)
                if cmd == fail_command:
                    fail_command = None
                    raise RuntimeError('fixture Jugg command failure')
                if cmd == 'findNearestBaseCommit':
                    pathlib.Path(params['output']).write_text(json.dumps({'matchedCommit': 'target'}))
                elif cmd == 'getChangedFiles':
                    pathlib.Path(params['output']).write_text(json.dumps({'files': changes}))
                else:
                    out = pathlib.Path(params.get('outputApkDir', project / 'build/jugg/classpath/apk'))
                    out.mkdir(parents=True, exist_ok=True)
                    (out / 'app.apk').write_bytes(b'apk fixture')
                    if cmd == 'buildGradleBase':
                        (project / 'build/jugg').mkdir(parents=True, exist_ok=True)
                        (project / 'build/jugg/history').write_text('clean')
                    elif params['changedFiles']:
                        self.assertEqual(params['changedFiles'], str(source))
                        pathlib.Path(params['baseBuildJuggRootDir'], '.dirty').touch()
                return ''

            with patch.object(demo, 'request', storage), patch.object(demo, 'run', command):
                base_apks = demo.build(project, work, args)
                for changed in ([], [{'type': 'Unknown', 'file': str(readme)}]):
                    changes[:] = changed
                    returned = demo.build(project, work, args)
                    self.assertEqual(returned[0].read_bytes(), base_apks[0].read_bytes())
                    result = json.loads((work / 'build-result.json').read_text())
                    self.assertEqual(result['mode'], 'reuseBaseApk')
                    self.assertIsNone(result['reason'])
                    self.assertEqual(len(records), 1)
                    self.assertEqual(base_apks[0].read_bytes(), b'apk fixture')
                changes.append({'type': 'Kotlin', 'file': str(source)})
                demo.build(project, work, args)
                changes[:] = [{'type': 'Unknown', 'file': str(project / 'config.json')}]
                (project / 'config.json').write_text('{}')
                demo.build(project, work, args)
                self.assertEqual(json.loads((work / 'build-result.json').read_text())['reason'],
                                 'unrecognized changed files')
                for failed in ('findNearestBaseCommit', 'getChangedFiles', 'buildIncrementalApk'):
                    fail_command = failed
                    changes[:] = [{'type': 'Kotlin', 'file': str(source)}]
                    demo.build(project, work, args)
                    result = json.loads((work / 'build-result.json').read_text())
                    self.assertEqual(result['mode'], 'buildGradleBase')
                    self.assertIn('fixture Jugg command failure', result['reason'])
                demo.build(project, work, args, True)
            self.assertEqual(commands.count('buildGradleBase'), 6)
            self.assertEqual(len(records), 6)
            self.assertTrue(all('apkPath' not in record for record in records))
            self.assertFalse(list(work.glob('runs/*/baseline')))
            self.assertEqual(len(list(work.glob('runs/*/apk'))), 3)
            self.assertFalse(pathlib.Path(records[0]['juggRootPath'], '.dirty').exists())
            self.assertTrue(list(work.glob('runs/*/jugg/.dirty')))
