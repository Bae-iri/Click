"""USB-only reverse proxy to the existing Click API for the real app-task provider audit.

No provider credentials are read. Authentication remains the isolated audit account's
normal API authentication. No deployment or dashboard endpoint is exposed.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import time
import httpx

BASE = 'https://click-db.onrender.com'
READ = {'/version', '/health', '/ready', '/openapi.json', '/api/v1/users/me'}
POST = {'/api/v1/auth/register', '/api/v1/auth/refresh', '/api/v1/agent/app-guide/task',
        '/api/v1/agent/app-guide/task/memory', '/api/v1/agent/app-guide/task/events',
        '/api/v1/agent/app-guide/task/result'}


def main():
    client = httpx.Client(base_url=BASE, timeout=35, follow_redirects=False)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def handle_api(self):
            path = self.path.split('?', 1)[0]
            allowed = self.command == 'GET' and path in READ or self.command == 'POST' and path in POST
            allowed |= self.command == 'DELETE' and path.startswith('/api/v1/users/') and len(path.rsplit('/', 1)[-1]) == 36
            if not allowed:
                self.send_error(404)
                return
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 <= size <= 512000:
                self.send_error(413)
                return
            body = self.rfile.read(size)
            headers = {'Content-Type': 'application/json'}
            for key in ('Authorization', 'X-Click-Execution-State', 'X-Click-Turn-Budget-Ms'):
                if self.headers.get(key):
                    headers[key] = self.headers[key]
            started = time.monotonic()
            try:
                response = client.request(self.command, path, content=body or None, headers=headers)
                self.send_response(response.status_code)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(response.content)))
                self.end_headers()
                self.wfile.write(response.content)
                if path.startswith('/api/v1/agent/'):
                    print('APP_AUDIT_FORWARD', path, response.status_code, round((time.monotonic()-started)*1000), flush=True)
            except (httpx.HTTPError, BrokenPipeError):
                if not self.wfile.closed:
                    try:
                        self.send_error(502)
                    except OSError:
                        pass

        do_GET = do_POST = do_DELETE = handle_api

    print('APP_AUDIT_PROXY http://127.0.0.1:8766 -> existing Click app-task API; no dashboard/deploy routes', flush=True)
    try:
        ThreadingHTTPServer(('127.0.0.1', 8766), Handler).serve_forever()
    finally:
        client.close()


if __name__ == '__main__':
    main()
