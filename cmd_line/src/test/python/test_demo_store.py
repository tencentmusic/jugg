import io
from datetime import datetime, timezone
import json
import zipfile
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch

from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import importlib.util

path = Path(__file__).resolve().parents[2] / 'demo/jugg_ci/base_build_server.py'
spec = importlib.util.spec_from_file_location('base_build_server', path)
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)


class ServerTestCase(unittest.TestCase):
    def setUp(self):
        self.store = service
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.zip_patch = patch.object(service, 'CMD_LINE_ZIP', self.root / 'cmd_line.zip', create=True)
        self.zip_patch.start()
        self.server = service.HTTPServer(('127.0.0.1', 0), service.Handler)
        self.server.data_file = str(self.root / 'base-builds.json')
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
        self.zip_patch.stop()
        self.directory.cleanup()


class DownloadTest(ServerTestCase):
    def test_download_returns_zip_bytes_and_attachment_headers(self):
        content = b'PK\x03\x04' + b'archive-data' * 100000
        (self.root / 'cmd_line.zip').write_bytes(content)
        with urllib.request.urlopen(self.url + '/cmd_line.zip') as response:
            self.assertEqual(response.headers['Content-Type'], 'application/zip')
            self.assertEqual(int(response.headers['Content-Length']), len(content))
            self.assertEqual(response.headers['Content-Disposition'], 'attachment; filename="cmd_line.zip"')
            self.assertEqual(response.read(), content)

    def test_missing_archive_returns_404_and_baseline_query_still_works(self):
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(self.url + '/cmd_line.zip')
        self.assertEqual(error.exception.code, 404)
        error.exception.close()
        with urllib.request.urlopen(self.url + '/base-builds') as response:
            self.assertEqual(response.read(), b'[]')


class BaselineTransferTest(ServerTestCase):
    def records(self, project='example', record=None, available=False):
        query = dict(projectId=project)
        if available:
            query.update(scenarioKey='debug', compileCommand='assembleDebug')
        path = '/base-builds/available' if available else '/base-builds'
        body = None if record is None else json.dumps(record).encode()
        req = Request(self.url + path + '?' + urlencode(query), data=body,
                      method='GET' if record is None else 'PUT')
        with urlopen(req, timeout=5) as response:
            return json.load(response)

    def upload(self):
        body = io.BytesIO()
        with zipfile.ZipFile(body, 'w') as archive:
            archive.writestr('jugg/classpath/apk/app.apk', b'apk fixture')
        payload = body.getvalue()
        req = Request(self.url + '/artifacts', data=payload, method='POST')
        before = datetime.now(timezone.utc)
        with urlopen(req, timeout=5) as response:
            uploaded = json.load(response)
        timestamp = datetime.fromisoformat(uploaded['uploadedAt'])
        self.assertGreaterEqual(timestamp, before)
        self.assertLessEqual(timestamp, datetime.now(timezone.utc))
        return uploaded['artifactId'], payload

    def delete(self, commit=None, project=None):
        query = {}
        if commit is not None:
            query['baseCommit'] = commit
        if project is not None:
            query['projectId'] = project
        with urlopen(Request(self.url + '/base-builds?' + urlencode(query), method='DELETE'), timeout=5) as response:
            return json.load(response)

    def test_delete_commit_removes_all_scenarios_and_projects_and_artifacts(self):
        first, _ = self.upload()
        second, _ = self.upload()
        self.records(record=dict(id='a', baseCommit='abc', artifactId=first))
        self.records('other', dict(id='b', baseCommit='abc', artifactId=second))
        self.records(record=dict(id='keep', baseCommit='def', artifactId='external-key'))
        result = self.delete('abc')
        self.assertEqual(2, result['deletedRecords'])
        self.assertEqual(2, result['deletedArtifacts'])
        self.assertEqual(['keep'], [r['id'] for r in self.records()])
        self.assertEqual([], self.records('other'))
        self.assertFalse((self.root / 'artifacts' / (first + '.zip')).exists())
        self.assertFalse((self.root / 'artifacts' / (second + '.zip')).exists())
        self.assertEqual(0, self.delete('abc')['deletedRecords'])

    def test_delete_project_scope_preserves_shared_artifact(self):
        artifact, _ = self.upload()
        self.records(record=dict(id='a', baseCommit='abc', artifactId=artifact))
        self.records('other', dict(id='b', baseCommit='abc', artifactId=artifact))
        result = self.delete('abc', 'example')
        self.assertEqual(1, result['deletedRecords'])
        self.assertEqual(0, result['deletedArtifacts'])
        self.assertTrue((self.root / 'artifacts' / (artifact + '.zip')).exists())
        self.assertEqual(1, len(self.records('other')))

    def test_delete_requires_exact_nonempty_commit_and_ignores_external_paths(self):
        outside = self.root / 'outside.zip'
        outside.write_bytes(b'keep')
        self.records(record=dict(id='a', baseCommit='abc', artifactId='../outside', juggRootPath=str(self.root)))
        for commit in (None, '', '   '):
            with self.assertRaises(HTTPError) as failure:
                self.delete(commit)
            self.assertEqual(400, failure.exception.code)
            failure.exception.close()
        self.assertEqual(0, self.delete('ab')['deletedRecords'])
        self.assertEqual(1, self.delete('abc')['deletedRecords'])
        self.assertTrue(outside.exists())

    def test_delete_save_failure_preserves_records_and_artifacts(self):
        artifact, _ = self.upload()
        saved = self.records(record=dict(id='a', baseCommit='abc', artifactId=artifact))
        with patch.object(service.Handler, '_save', side_effect=OSError('disk full')):
            with self.assertRaises(HTTPError) as failure:
                self.delete('abc')
            self.assertEqual(500, failure.exception.code)
            failure.exception.close()
        self.assertEqual([saved], self.records())
        self.assertTrue((self.root / 'artifacts' / (artifact + '.zip')).exists())

    def test_server_timestamp_is_persisted_and_queries_sort_newest_first(self):
        early = datetime(2026, 11, 18, 1, tzinfo=timezone.utc)
        late = datetime(2026, 11, 18, 2, tzinfo=timezone.utc)
        record = dict(scenarioKey='debug', compileCommand='assembleDebug',
                      uploadedAt='2099-01-01T00:00:00+00:00', createdAt='client-business-time')
        with patch.object(service, 'datetime', create=True) as clock:
            clock.now.return_value = early
            first = self.records(record=dict(record, id='first'))
            clock.now.return_value = late
            second = self.records(record=dict(record, id='second'))
        self.assertEqual(first['uploadedAt'], early.isoformat())
        self.assertEqual(second['uploadedAt'], late.isoformat())
        self.assertEqual(second['createdAt'], 'client-business-time')
        # Shuffle storage to verify sorting uses timestamps, not list order.
        legacy = dict(id='legacy', projectId='example', scenarioKey='debug', compileCommand='assembleDebug')
        Path(self.server.data_file).write_text(json.dumps([second, legacy, first]))
        for available in (False, True):
            result = self.records(available=available)
            self.assertEqual([r['id'] for r in result], ['second', 'first', 'legacy'])
            self.assertIsNone(result[-1]['uploadedAt'])
        with patch.object(service, 'datetime', create=True) as clock:
            clock.now.return_value = late
            replacement = self.records(record=dict(record, id='first'))
        self.assertEqual(replacement['uploadedAt'], late.isoformat())
        self.assertEqual([r['id'] for r in self.records()], ['first', 'second', 'legacy'])
        self.assertEqual(json.loads(Path(self.server.data_file).read_text())[-1], replacement)

    def test_records_round_trip_opaque_storage_fields_without_accessing_files(self):
        for key in ('bucket/path/build.zip', 'custom-storage:key', 'http://storage.invalid/file'):
            record = dict(id='base', projectId='example', artifactId=key,
                          juggRootPath='/not-a-server-path', metadata={'arbitrary': ['value']})
            saved = self.records(record=record)
            self.assertEqual({k: v for k, v in saved.items() if k != 'uploadedAt'}, record)
            self.assertEqual(self.records(), [saved])
        self.assertFalse((self.root / 'base_build').exists())
        self.assertFalse((self.root / 'artifacts').exists())

    def test_artifact_upload_then_record_and_download_are_independent(self):
        artifact_id, payload = self.upload()
        self.assertEqual(self.records(), [])
        record = dict(id='base', projectId='example', artifactId=artifact_id)
        saved = self.records(record=record)
        self.assertEqual({k: v for k, v in saved.items() if k != 'uploadedAt'}, record)
        stored = self.records()[0]
        with urlopen(self.url + '/artifacts/' + stored['artifactId']) as response:
            self.assertEqual(response.headers['Content-Type'], 'application/zip')
            self.assertEqual(response.read(), payload)
        self.records(record=dict(record, artifactId='external-key'))
        with urlopen(self.url + '/artifacts/' + artifact_id) as response:
            self.assertEqual(response.read(), payload)

    def test_each_project_retains_100_records_without_deleting_artifacts(self):
        artifact_id, payload = self.upload()
        record = dict(id='shared', scenarioKey='debug', compileCommand='assembleDebug', artifactId=artifact_id)
        self.records('other', record)
        for index in range(105):
            self.records(record=dict(record, id=str(index)))
        self.assertEqual([r['id'] for r in self.records()], list(map(str, range(104, 4, -1))))
        self.records(record=dict(record, id='5', artifactId='external-key'))
        self.assertEqual(self.records(available=True)[0]['artifactId'], 'external-key')
        self.assertEqual([r['id'] for r in self.records('other')], ['shared'])
        with urlopen(self.url + '/artifacts/' + artifact_id) as response:
            self.assertEqual(response.read(), payload)

    def test_record_failure_preserves_index_and_uploaded_artifact(self):
        artifact_id, payload = self.upload()
        original = self.records(record=dict(id='base', artifactId=artifact_id))
        with patch.object(self.store.Handler, '_save', side_effect=OSError('disk full')):
            with self.assertRaises(HTTPError) as failure:
                self.records(record=dict(id='base', artifactId='replacement'))
            self.assertEqual(failure.exception.code, 500)
            failure.exception.close()
        self.assertEqual(self.records(), [original])
        with urlopen(self.url + '/artifacts/' + artifact_id) as response:
            self.assertEqual(response.read(), payload)

    def test_invalid_records_and_missing_artifact_return_errors(self):
        for record in ({}, {'id': ''}, {'id': 'base', 'projectId': 'wrong'}, []):
            with self.subTest(record=record), self.assertRaises(HTTPError) as failure:
                self.records(record=record)
            self.assertEqual(failure.exception.code, 400)
            failure.exception.close()
        for identifier in ('missing', '..%2fbase-builds.json'):
            with self.assertRaises(HTTPError) as failure:
                urlopen(self.url + '/artifacts/' + identifier)
            self.assertEqual(failure.exception.code, 404)
            failure.exception.close()


if __name__ == '__main__':
    unittest.main()
