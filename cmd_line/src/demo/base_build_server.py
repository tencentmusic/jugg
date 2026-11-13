#!/usr/bin/env python3
import argparse, json, os, tempfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse
class Handler(BaseHTTPRequestHandler):
    def _data(self):
        p = self.server.data_file
        try: return json.loads(open(p).read())
        except FileNotFoundError: return []
    def _send(self, code, value):
        body=json.dumps(value).encode(); self.send_response(code); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        data=self._data(); q=parse_qs(urlparse(self.path).query)
        if self.path.startswith('/base-builds/available'): data=[r for r in data if all(r.get(k)==q.get(k,[''])[0] for k in ('scenarioKey','compileCommand'))]
        elif self.path != '/base-builds': return self._send(404, {'error':'not found'})
        self._send(200,data)
    def do_POST(self):
        if self.path != '/base-builds': return self._send(404, {'error':'not found'})
        record=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0')))); data=[r for r in self._data() if r.get('id') != record.get('id')]; data.append(record)
        os.makedirs(os.path.dirname(self.server.data_file) or '.', exist_ok=True); fd,tmp=tempfile.mkstemp(dir=os.path.dirname(self.server.data_file) or '.'); os.close(fd); open(tmp,'w').write(json.dumps(data,indent=2)); os.replace(tmp,self.server.data_file); self._send(200,record)
def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--host',default='127.0.0.1'); ap.add_argument('--port',type=int,default=18765); ap.add_argument('--data-file',default='.demo-ci/base-builds.json'); a=ap.parse_args(); s=HTTPServer((a.host,a.port),Handler); s.data_file=a.data_file; s.serve_forever()
if __name__ == '__main__': main()
