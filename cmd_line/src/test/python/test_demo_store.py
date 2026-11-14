"""Protect HTTP project isolation, retention and the script-relative storage default."""
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import HTTPServer
from urllib.parse import urlencode
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / 'demo/scripts/base_build_server.py'


def request(server, project, record=None, available=False, method=None):
    query = {'projectId': project}
    if available:
        query.update(scenarioKey='debug', compileCommand='assembleDebug')
    path = '/base-builds/available' if available else '/base-builds'
    body = None if record is None else json.dumps(record).encode()
    with urlopen(Request(server + path + '?' + urlencode(query), data=body, method=method), timeout=5) as response:
        return json.load(response)


class DemoStoreTest(unittest.TestCase):
    def test_each_project_retains_100_recent_saves_and_scopes_record_ids(self):
        spec = importlib.util.spec_from_file_location('demo_store', SCRIPT)
        store = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(store)
        with tempfile.TemporaryDirectory() as directory:
            server = HTTPServer(('127.0.0.1', 0), store.Handler)
            server.data_file = str(Path(directory) / 'base-builds.json')
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            url = f'http://127.0.0.1:{server.server_port}'
            source = Path(directory) / 'source-jugg'
            source.mkdir()
            (source / 'history').write_text('clean')
            record = dict(id='shared', scenarioKey='debug', compileCommand='assembleDebug',
                          juggRootPath=str(source))
            Path(server.data_file).write_text(json.dumps([dict(record, id='legacy')]))
            try:
                self.assertEqual([r['id'] for r in request(url, 'default')], ['legacy'])
                request(url, 'project-b', record)
                for index in range(105):
                    request(url, 'project-a', dict(record, id=str(index)))
                records = request(url, 'project-a')
                self.assertEqual([r['id'] for r in records], list(map(str, range(5, 105))))
                request(url, 'project-a', dict(record, id='5', value='updated'))
                records = request(url, 'project-a', available=True)
                self.assertEqual(len(records), 100)
                self.assertEqual(records[-1]['value'], 'updated')
                request(url, 'project-a', record)
                self.assertEqual(len(request(url, 'project-a')), 100)
                self.assertEqual([r['id'] for r in request(url, 'project-b')], ['shared'])
                self.assertEqual(request(url, 'unknown'), [])
                self.assertEqual(len(json.loads(Path(server.data_file).read_text())), 102)
                archive = Path(directory) / 'base_build'
                self.assertEqual(len(list((archive / 'project-a').iterdir())), 100)
                self.assertFalse((archive / 'project-a/6').exists())
                self.assertTrue((archive / 'project-b/shared/history').is_file())
            finally:
                server.shutdown()
                thread.join()
                server.server_close()

    def test_put_archives_jugg_root_and_logs_requests_and_get_results(self):
        spec = importlib.util.spec_from_file_location('demo_store', SCRIPT)
        store = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(store)
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()) as logs:
            root = Path(directory)
            source = root / 'source-jugg'
            (source / 'classpath/apk').mkdir(parents=True)
            (source / 'classpath/apk/app.apk').write_bytes(b'first APK')
            (source / 'stale').touch()
            server = HTTPServer(('127.0.0.1', 0), store.Handler)
            server.data_file = str(root / 'base-builds.json')
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            url = f'http://127.0.0.1:{server.server_port}'
            try:
                record = dict(id='base', juggRootPath=str(source), scenarioKey='debug',
                              compileCommand='assembleDebug')
                saved = request(url, 'example', record, method='PUT')
                destination = root / 'base_build/example/base'
                self.assertEqual(saved['juggRootPath'], str(destination))
                self.assertNotIn('apkPath', saved)
                self.assertEqual((destination / 'classpath/apk/app.apk').read_bytes(), b'first APK')
                (source / 'stale').unlink()
                (source / 'classpath/apk/app.apk').write_bytes(b'updated APK')
                request(url, 'example', record, method='PUT')
                self.assertFalse((destination / 'stale').exists())
                self.assertEqual((destination / 'classpath/apk/app.apk').read_bytes(), b'updated APK')
                self.assertEqual(request(url, 'example', available=True), [saved])
                self.assertEqual(request(url, 'example', saved, method='PUT'), saved)
                self.assertIn('PUT 请求参数', logs.getvalue())
                self.assertIn('GET 请求参数', logs.getvalue())
                self.assertIn('GET 返回列表', logs.getvalue())
                self.assertIn(json.dumps([saved], ensure_ascii=False), logs.getvalue())
            finally:
                server.shutdown()
                thread.join()
                server.server_close()

    def test_failed_save_preserves_previous_archive_and_rejects_directory_traversal(self):
        spec = importlib.util.spec_from_file_location('demo_store', SCRIPT)
        store = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(store)
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            root = Path(directory)
            source = root / 'source'
            source.mkdir()
            (source / 'history').write_text('original')
            server = HTTPServer(('127.0.0.1', 0), store.Handler)
            server.data_file = str(root / 'base-builds.json')
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            url = f'http://127.0.0.1:{server.server_port}'
            record = dict(id='base', juggRootPath=str(source))
            try:
                original = request(url, 'example', record, method='PUT')
                (source / 'history').write_text('replacement')
                with patch.object(store.Handler, '_save', side_effect=OSError('index is read-only')):
                    with self.assertRaises(HTTPError) as failure:
                        request(url, 'example', record, method='PUT')
                    self.assertEqual(failure.exception.code, 500)
                    failure.exception.close()
                self.assertEqual(request(url, 'example'), [original])
                self.assertEqual((Path(original['juggRootPath']) / 'history').read_text(), 'original')
                for project, identifier in (('../escape', 'base'), ('example', '../escape')):
                    with self.assertRaises(HTTPError) as failure:
                        request(url, project, dict(record, id=identifier), method='PUT')
                    self.assertEqual(failure.exception.code, 400)
                    failure.exception.close()
                self.assertFalse((root / 'escape').exists())
            finally:
                server.shutdown()
                thread.join()
                server.server_close()

    def test_default_storage_is_next_to_script_when_started_from_another_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'scripts'
            scripts.mkdir()
            shutil.copy2(SCRIPT, scripts / SCRIPT.name)
            process = subprocess.Popen([sys.executable, str(scripts / SCRIPT.name), '--port', '0'],
                                       cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                lines = queue.Queue()
                threading.Thread(target=lambda: lines.put(process.stdout.readline()), daemon=True).start()
                address = lines.get(timeout=5).strip().split('：', 1)[-1]
                source = root / 'source-jugg'
                source.mkdir()
                (source / 'history').write_text('clean')
                request(address, 'example', {'id': 'base', 'juggRootPath': str(source)})
                self.assertTrue((scripts / 'base-builds.json').is_file())
                self.assertTrue((scripts / 'base_build/example/base/history').is_file())
                self.assertFalse((root / 'base-builds.json').exists())
            finally:
                process.terminate()
                process.communicate(timeout=5)


if __name__ == '__main__':
    unittest.main()
