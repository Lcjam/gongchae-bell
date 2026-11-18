"""Helpers for alert-e2e.sh, run inside the discord-stub container (monitoring network).

serve        Discord webhook stand-in: records every POST body, answers 204, lists them on GET.
messages     Prints the recorded webhook bodies as JSON.
has W...     Exits 0 if one recorded body contains every word W (case-insensitive).
query EXPR   Exits 0 if the Prometheus instant query EXPR returns at least one sample.
queries      Runs every dashboard query against Prometheus and fails on any query error.
"""
import http.server
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

received = []


class Webhook(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        received.append(json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0)))))
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        body = json.dumps(received, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def prometheus(expr):
    url = "http://prometheus:9090/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    try:
        return json.load(urllib.request.urlopen(url, timeout=10))
    except urllib.error.HTTPError as error:  # a bad query is a 400 with the reason in the body
        return json.load(error)


def recorded():
    return json.load(urllib.request.urlopen("http://127.0.0.1:8080/", timeout=5))


def queries():
    dashboard = json.load(open("/dashboards/gongchae.json", encoding="utf-8"))
    exprs = [t["expr"] for p in dashboard["panels"] for t in p.get("targets", [])]
    for expr in exprs:
        result = prometheus(expr.replace("$__range", "1h"))
        if result.get("status") != "success":
            sys.exit(f"QUERY_FAILED {expr}: {result}")
    print(f"dashboard queries ok: {len(exprs)}")


if __name__ == "__main__":
    command = sys.argv[1]
    if command == "serve":
        http.server.ThreadingHTTPServer(("", 8080), Webhook).serve_forever()
    elif command == "messages":
        print(json.dumps(recorded(), ensure_ascii=False, indent=1))
    elif command == "has":
        words = [w.lower() for w in sys.argv[2:]]
        sys.exit(0 if any(all(w in json.dumps(m, ensure_ascii=False).lower() for w in words) for m in recorded()) else 1)
    elif command == "query":
        sys.exit(0 if prometheus(sys.argv[2]).get("data", {}).get("result") else 1)
    elif command == "queries":
        queries()
