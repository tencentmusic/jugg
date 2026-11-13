import importlib.util
import pathlib
import unittest

PATH = pathlib.Path(__file__).resolve().parents[2] / 'demo' / 'jugg_ci_demo.py'

class DemoPolicyTest(unittest.TestCase):
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
            records, commands = [], []
            args = SimpleNamespace(scenario='debug', server='http://localhost', cmd_line='fixture')

            def storage(server, path, record=None):
                if record:
                    records.append(record)
                return records

            def command(argv, cwd):
                if argv[0] == 'git':
                    return 'target'
                params = dict(arg.split('=', 1) for arg in argv[1:])
                cmd = params['cmd']
                commands.append(cmd)
                if cmd == 'findNearestBaseCommit':
                    pathlib.Path(params['output']).write_text(json.dumps({'matchedCommit': 'target'}))
                elif cmd == 'getChangedFiles':
                    pathlib.Path(params['output']).write_text(json.dumps({'files': [{'type': 'Kotlin', 'file': str(source)}]}))
                else:
                    out = pathlib.Path(params['outputApkDir'])
                    out.mkdir()
                    (out / 'app.apk').write_bytes(b'apk fixture')
                    if cmd == 'buildGradleBase':
                        (project / 'build/jugg').mkdir(parents=True, exist_ok=True)
                        (project / 'build/jugg/history').write_text('clean')
                    else:
                        pathlib.Path(params['baseBuildJuggRootDir'], '.dirty').touch()
                return ''

            with patch.object(demo, 'request', storage), patch.object(demo, 'run', command):
                demo.build(project, work, args)
                demo.build(project, work, args)
                demo.build(project, work, args, True)
            self.assertEqual(commands, ['buildGradleBase', 'findNearestBaseCommit', 'getChangedFiles',
                                        'buildIncrementalApk', 'findNearestBaseCommit', 'getChangedFiles', 'buildGradleBase'])
            self.assertEqual(len(records), 2)
            self.assertFalse(pathlib.Path(records[0]['juggRootPath'], '.dirty').exists())
            self.assertTrue(list(work.glob('runs/*/jugg/.dirty')))
