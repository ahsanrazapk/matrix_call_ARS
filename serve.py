import http.server
import socketserver
import os

PORT = 5000
BUILD_DIR = os.path.join(os.path.dirname(__file__), 'build', 'web')

class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=BUILD_DIR, **kwargs)

    def log_message(self, format, *args):
        pass

socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("0.0.0.0", PORT), Handler) as httpd:
    print(f"Serving Flutter web app on port {PORT}")
    httpd.serve_forever()
