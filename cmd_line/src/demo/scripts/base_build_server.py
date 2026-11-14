#!/usr/bin/env python3
"""本机演示基线服务：按项目归档完整 Jugg 基线，APK 已包含在 Jugg 目录中。"""
import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

# 默认文件与脚本同级，不依赖启动服务时所在的工作目录。
DEFAULT_DATA_FILE = Path(__file__).resolve().with_name('base-builds.json')
MAX_RECORDS_PER_PROJECT = 100


class Handler(BaseHTTPRequestHandler):
    def _data(self):
        try:
            return json.loads(Path(self.server.data_file).read_text(encoding='utf-8'))
        except FileNotFoundError:
            return []

    def _send(self, code, value):
        body = json.dumps(value, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        if url.path not in ('/base-builds', '/base-builds/available'):
            return self._send(404, {'error': '接口不存在'})
        query = parse_qs(url.query)
        project_id = query.get('projectId', ['default'])[0]
        print('GET 请求参数：' + json.dumps(dict(path=url.path, query=query), ensure_ascii=False), flush=True)
        # 无 projectId 的历史记录归入 default，保留旧示例数据的可读性。
        data = [r for r in self._data() if r.get('projectId', 'default') == project_id]
        if url.path == '/base-builds/available':
            data = [r for r in data if all(r.get(key) == query.get(key, [''])[0]
                                          for key in ('scenarioKey', 'compileCommand'))]
        print('GET 返回列表：' + json.dumps(data, ensure_ascii=False), flush=True)
        self._send(200, data)

    def do_PUT(self):
        url = urlparse(self.path)
        if url.path != '/base-builds':
            return self._send(404, {'error': '接口不存在'})
        query = parse_qs(url.query)
        project_id = query.get('projectId', ['default'])[0]
        try:
            record = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
            print(self.command + ' 请求参数：' + json.dumps(
                dict(path=url.path, query=query, record=record), ensure_ascii=False), flush=True)
            self._validate(record, project_id)
        except ValueError:
            return self._send(400, {'error': '需要有效 JSON、单层 projectId/id 和存在的 juggRootPath，项目标识须一致'})
        record['projectId'] = project_id
        record.pop('apkPath', None)
        data = self._data()
        others = [r for r in data if r.get('projectId', 'default') != project_id]
        current = [r for r in data if r.get('projectId', 'default') == project_id
                   and r.get('id') != record['id']]
        # 以服务接收保存的顺序判断最近；覆盖同一 id 也视为一次最新保存。
        current.append(record)
        try:
            self._archive(record, others + current[-MAX_RECORDS_PER_PROJECT:])
        except OSError as exception:
            return self._send(500, {'error': f'基线保存失败：{exception}'})
        self._prune(project_id, current[:-MAX_RECORDS_PER_PROJECT])
        self._send(200, record)

    # 保留旧演示客户端使用 POST 写入的兼容入口。
    do_POST = do_PUT

    def _validate(self, record, project_id):
        if not isinstance(record, dict) or record.get('projectId', project_id) != project_id:
            raise ValueError('项目标识不一致')
        # 两个标识都只占一层目录，避免写入 base_build 之外。
        for value in (project_id, record.get('id')):
            if not isinstance(value, str) or not value or value in ('.', '..') or '/' in value or '\\' in value:
                raise ValueError('无效目录标识')
        source = record.get('juggRootPath')
        if not isinstance(source, str) or not source or not Path(source).is_dir():
            raise ValueError('基线目录不存在')
        destination = Path(self.server.data_file).parent / 'base_build' / project_id / record['id']
        if Path(source).resolve() in destination.resolve().parents:
            raise ValueError('归档目录不能位于源目录内')

    def _archive(self, record, data):
        destination = Path(self.server.data_file).parent / 'base_build' / record['projectId'] / record['id']
        destination.parent.mkdir(parents=True, exist_ok=True)
        # 先复制完整目录再替换；覆盖同 ID 时不会残留旧文件。
        with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
            staged, previous = Path(temporary) / 'new', Path(temporary) / 'previous'
            shutil.copytree(record['juggRootPath'], staged)
            if destination.exists():
                destination.rename(previous)
            try:
                staged.rename(destination)
                record['juggRootPath'] = str(destination)
                self._save(data)
            except OSError:
                # 索引写入失败时恢复同 ID 的旧归档，不留下半次成功的覆盖。
                if destination.exists():
                    shutil.rmtree(destination)
                if previous.exists():
                    previous.rename(destination)
                raise

    def _prune(self, project_id, records):
        root = Path(self.server.data_file).parent / 'base_build' / project_id
        for record in records:
            # 只清理本服务拥有的归档，旧元数据指向的外部目录不参与删除。
            destination = root / str(record['id'])
            if destination.parent != root or destination.name in ('.', '..') or not destination.is_dir():
                continue
            try:
                shutil.rmtree(destination)
            except OSError as exception:
                print(f'基线已保存，清理过期归档失败：{destination}：{exception}', flush=True)

    def _save(self, data):
        path = Path(self.server.data_file)
        path.parent.mkdir(parents=True, exist_ok=True)
        # 同目录临时文件加原子替换，写入失败时保留上一份完整数据。
        fd, temporary = tempfile.mkstemp(dir=path.parent)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump(data, stream, ensure_ascii=False, indent=2)
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=18766)
    parser.add_argument('--data-file', type=Path, default=DEFAULT_DATA_FILE,
                        help='元数据文件，默认在脚本同级保存 base-builds.json')
    args = parser.parse_args()
    # 一个服务处理多个 projectId，项目由每次 HTTP 请求指定，不在启动时绑定。
    server = HTTPServer((args.host, args.port), Handler)
    server.data_file = str(args.data_file.resolve())
    print(f'基线服务：http://{args.host}:{server.server_port}', flush=True)
    print(f'数据文件：{server.data_file}；每个项目最多保留 {MAX_RECORDS_PER_PROJECT} 条记录', flush=True)
    print(f'基线目录：{Path(server.data_file).parent / "base_build"}/{{projectId}}/{{id}}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
