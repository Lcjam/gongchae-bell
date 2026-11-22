#!/usr/bin/env python3
"""Local HTTP test-double unit tests for deploy/perf/perf-probe.py (#18).

Uses a local ThreadingHTTPServer emitting realistic Thymeleaf-shaped HTML (mirroring
home.html, jobs.html, job-detail.html, institution.html and JobSearchCriteria.normalize).
Does NOT call the live public site, external APIs, or providers, and uses sub-second timeouts.
"""
from __future__ import annotations

import csv
import html
import http.server
import importlib.util
import json
import pathlib
import signal
import socketserver
import tempfile
import threading
import time
import unittest
import urllib.parse

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
PROBE_PATH = REPO_ROOT / "deploy" / "perf" / "perf-probe.py"

SPEC = importlib.util.spec_from_file_location("perf_probe", PROBE_PATH)
assert SPEC and SPEC.loader
perf_probe = importlib.util.module_from_spec(SPEC)
import sys
sys.modules["perf_probe"] = perf_probe
SPEC.loader.exec_module(perf_probe)


ALLOWED_NCS = {"R600020": "정보통신", "R600002": "경영.회계.사무"}
ALLOWED_REGION = {"R3010": "서울", "R3013": "대구"}
ALLOWED_HIRE = {"R1010": "정규직"}
ALLOWED_CAREER = {"newcomer", "newonly", "career"}
ALLOWED_STATUS = {"open", "check", "closed", "all"}
ALLOWED_SORT = {"deadline", "begin"}
VALID_SNS = {"307027", "307028", "307029", "307030", "307031"}
VALID_INSTS = {"C0008", "C0661", "C0001", "C0002", "C0003"}


def render_jobs_html(qs: dict[str, list[str]]) -> str:
    """Faithfully reproduces JobSearchCriteria.normalize + jobs.html markers."""
    raw_ncs = (qs.get("ncs") or [""])[0].strip()
    raw_region = (qs.get("region") or [""])[0].strip()
    raw_hire = (qs.get("hire") or [""])[0].strip()
    raw_career = (qs.get("career") or [""])[0].strip()
    raw_status = (qs.get("status") or [""])[0].strip()
    raw_sort = (qs.get("sort") or [""])[0].strip()
    raw_q = (qs.get("q") or [""])[0].strip()
    raw_page = (qs.get("page") or ["1"])[0].strip()

    ncs = raw_ncs if raw_ncs in ALLOWED_NCS else ""
    region = raw_region if raw_region in ALLOWED_REGION else ""
    hire = raw_hire if raw_hire in ALLOWED_HIRE else ""
    career = raw_career if raw_career in ALLOWED_CAREER else ""
    status = raw_status if raw_status in ALLOWED_STATUS else "open"
    sort = raw_sort if raw_sort in ALLOWED_SORT else "deadline"
    q = raw_q[:100] if raw_q else ""
    try:
        req_page = max(1, int(raw_page))
    except ValueError:
        req_page = 1

    # Total count: notice sort=begin has IDENTICAL totalCount to default /jobs (120)!
    if q == "nomatch":
        total = 0
    elif status == "all":
        total = 250
    elif ncs or region or hire or career:
        total = 42
    else:
        total = 120

    total_pages = max(1, (total + 19) // 20)
    clamped_page = min(req_page, total_pages)
    has_active = any([ncs, region, hire, career, q, status != "open", sort != "deadline"])
    panel_cls = "border rounded-4 bg-white p-3 p-md-4 mb-4 d-md-block" + ("" if has_active else " d-none")

    def opts(mapping: dict[str, str], selected: str) -> str:
        out = ['<option value="">전체</option>']
        for k, v in mapping.items():
            sel = ' selected="selected"' if k == selected else ""
            out.append(f'<option value="{k}"{sel}>{v}</option>')
        return "".join(out)

    def enum_opts(vals: tuple[str, ...], selected: str) -> str:
        return "".join(
            f'<option value="{v}"{" selected=\"selected\"" if v == selected else ""}>{v}</option>' for v in vals
        )

    nav = ""
    if total_pages > 1:
        nav = (
            '<nav aria-label="공고 목록 페이지"><ul class="pagination">'
            f'<li class="page-item active"><a class="page-link" href="/jobs?page={clamped_page}">{clamped_page}</a></li>'
            "</ul></nav>"
        )

    return (
        "<!doctype html><html lang='ko'><body><main id='main'>"
        "<h1>채용공고</h1>"
        f'<form id="jobs-filter-panel" class="{panel_cls}">'
        f'<select id="filter-ncs" name="ncs">{opts(ALLOWED_NCS, ncs)}</select>'
        f'<select id="filter-region" name="region">{opts(ALLOWED_REGION, region)}</select>'
        f'<select id="filter-hire" name="hire">{opts(ALLOWED_HIRE, hire)}</select>'
        f'<select id="filter-career" name="career">{enum_opts(("newcomer", "newonly", "career"), career)}</select>'
        f'<select id="filter-status" name="status">{enum_opts(("open", "check", "closed", "all"), status)}</select>'
        f'<select id="filter-sort" name="sort">{enum_opts(("deadline", "begin"), sort)}</select>'
        f'<input id="filter-q" name="q" value="{html.escape(q, quote=True)}">'
        "</form>"
        f'<p>검색 결과 <strong class="text-dark">{total}</strong>건</p>'
        f"{nav}"
        "</main></body></html>"
    )


class FakeState:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.requests: list[dict[str, object]] = []
        self.conn_ids: list[int] = []
        self.mode = "ok"
        self.call_seq = 0
        self.preflight_count = 0
        self.inflight_barrier: threading.Barrier | None = None
        self.stop_file_to_create: pathlib.Path | None = None
        self.stop_after_calls: int = 0


class FakeServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, state: FakeState) -> None:
        self.state = state
        super().__init__(("127.0.0.1", 0), FakeHandler)


class FakeHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: object) -> None:
        return

    def do_GET(self) -> None:
        st: FakeState = self.server.state  # type: ignore[attr-defined]
        parsed = urllib.parse.urlsplit(self.path)
        qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)

        with st.lock:
            st.call_seq += 1
            seq = st.call_seq
            st.requests.append(
                {
                    "path": self.path,
                    "internal": self.headers.get("X-Gongchae-Internal"),
                    "ua": self.headers.get("User-Agent"),
                    "conn_id": id(self.connection),
                }
            )
            st.conn_ids.append(id(self.connection))
            mode = st.mode
            if st.stop_file_to_create and st.stop_after_calls and seq >= st.stop_after_calls:
                st.stop_file_to_create.write_text("stop", encoding="utf-8")

        # Fault injection modes (triggered only after preflight completes by switching mode, or on measure)
        if mode == "close_once" and seq == 2:
            self.close_connection = True
            self._send_html(200, "<!doctype html><html><body><h1 id='closing-heading'>h</h1><h2 id='today-heading'>t</h2></body></html>")
            return
        if mode == "drop_conn" and seq >= 12 and seq % 4 == 0:
            # Drop TCP socket abruptly without response
            self.close_connection = True
            return
        if mode == "fail_5xx" and seq >= 10:
            self._send_html(503, "error")
            return
        if mode == "fail_timeout" and seq >= 10:
            time.sleep(0.25)
            self._send_html(200, "late")
            return
        if mode == "fail_redirect_rate" and seq >= 12 and seq % 2 == 0:
            self.send_response(302)
            self.send_header("Location", "https://external.example/should-not-follow")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if mode == "mixed_errors" and seq == 18:
            self.send_response(302)
            self.send_header("Location", "https://external.example/nope")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if mode == "mixed_errors" and seq == 22:
            time.sleep(0.25)
            self._send_html(200, "late")
            return
        if mode == "slow_10ms":
            time.sleep(0.01)
        if mode == "slow_80ms" and seq > st.preflight_count:
            time.sleep(0.08)
        if mode == "fail_warmup_5xx" and seq > st.preflight_count:
            self._send_html(503, "warmup error")
            return
        if mode == "fail_warmup_timeout" and seq > st.preflight_count:
            time.sleep(0.25)
            self._send_html(200, "late")
            return
        if mode == "fail_warmup_redirect" and seq > st.preflight_count:
            self.send_response(302)
            self.send_header("Location", "https://external.example/warmup-redirect")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if mode == "c4_warmup_5xx_inflight" and seq > st.preflight_count:
            if st.inflight_barrier is not None and seq <= st.preflight_count + 4:
                try:
                    st.inflight_barrier.wait()
                except threading.BrokenBarrierError:
                    pass
            if seq == st.preflight_count + 4:
                # Delay 4th in-flight worker slightly so threshold (3) triggers while 4th is still in-flight
                time.sleep(0.05)
            self._send_html(503, "c4 warmup error")
            return
        if mode == "c4_warmup_barrier_waiting_abort" and seq > st.preflight_count:
            # First 2 post-preflight requests succeed quickly so one worker finishes warmup and waits at barrier,
            # while subsequent requests return 503 after a brief delay and trigger ABORTED_SAFETY.
            if seq <= st.preflight_count + 2:
                pass
            else:
                time.sleep(0.04)
                self._send_html(503, "barrier abort error")
                return

        route = parsed.path
        if route == "/":
            body = (
                "<!doctype html><html><body><main>"
                "<h1>나에게 맞는 공고</h1>"
                "<h2 id='closing-heading'>곧 마감해요</h2>"
                "<h2 id='today-heading'>오늘 시작한 공고</h2>"
                "</main></body></html>"
            )
            self._send_html(200, body)
        elif route == "/jobs":
            self._send_html(200, render_jobs_html(qs))
        elif route.startswith("/jobs/"):
            sn = route.split("/", 2)[2]
            if sn in VALID_SNS:
                body = (
                    "<!doctype html><html><body><main>"
                    '<a href="/jobs">← 채용공고 목록</a>'
                    f"<h1>공고 {sn}</h1>"
                    "</main></body></html>"
                )
                self._send_html(200, body)
            else:
                self._send_html(404, "not found")
        elif route.startswith("/institutions/"):
            code = route.split("/", 2)[2]
            if code in VALID_INSTS:
                body = (
                    "<!doctype html><html><body><main>"
                    f"<h1>기관 {code}</h1>"
                    "<h2 id='yearly-recruit-heading'>연도별</h2>"
                    "<h2 id='history-heading'>공고 이력</h2>"
                    "</main></body></html>"
                )
                self._send_html(200, body)
            else:
                self._send_html(404, "not found")
        else:
            self._send_html(404, "not found")

    def _send_html(self, status: int, text: str) -> None:
        raw = text.encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            if self.close_connection:
                self.send_header("Connection", "close")
            else:
                self.send_header("Connection", "keep-alive")
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError, OSError):
            self.close_connection = True


VALID_URLS_TSV = """group\tcase_id\tpath
home\thome_root\t/
list\tjobs_default\t/jobs
filter\tcareer_newcomer\t/jobs?career=newcomer
filter\tregion_ncs\t/jobs?region=R3010&ncs=R600020&status=open
filter\tkeyword_all\t/jobs?q=채용&status=all
filter\tdeep_page\t/jobs?status=all&page=10
filter\tsort_begin\t/jobs?sort=begin
detail\td1\t/jobs/307027
institution\ti1\t/institutions/C0008
"""


class PerfProbeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.state = FakeState()
        self.server = FakeServer(self.state)
        self.port = self.server.server_address[1]
        self.base_url = f"http://127.0.0.1:{self.port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.tmp = tempfile.TemporaryDirectory()
        self.tmp_path = pathlib.Path(self.tmp.name)

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def write_urls(self, content: str, name: str = "urls.tsv") -> pathlib.Path:
        p = self.tmp_path / name
        p.write_text(content, encoding="utf-8")
        return p

    def test_preflight_accepts_valid_filters_including_sort_begin_with_same_count(self) -> None:
        urls = self.write_urls(VALID_URLS_TSV)
        rc = perf_probe.main(["--base-url", self.base_url, "--urls", str(urls), "--preflight-only"])
        self.assertEqual(rc, 0)
        # Verify internal header and UA were sent on every request
        self.assertTrue(len(self.state.requests) >= 9)
        for req in self.state.requests:
            self.assertEqual(req["internal"], "1")
            self.assertEqual(req["ua"], "gongchae-probe/perf-18")

    def test_preflight_rejects_uppercase_enum_invalid_code_clamped_page_empty_q_and_forbidden_paths(self) -> None:
        bad_cases = [
            "filter\tbad_status\t/jobs?status=ALL\n",
            "filter\tbad_open\t/jobs?status=OPEN\n",
            "filter\tbad_sort\t/jobs?sort=start\n",
            "filter\tbad_ncs\t/jobs?ncs=R999999\n",
            "filter\tclamped_page\t/jobs?status=all&page=99\n",
            "filter\tempty_q\t/jobs?q=nomatch&status=all\n",
            "detail\tmissing_sn\t/jobs/999999\n",
            "institution\tmissing_inst\t/institutions/UNKNOWN\n",
            "detail\tforbidden_src\t/jobs/307027/source\n",
            "home\tforbidden_sub\t/subscriptions/new\n",
        ]
        for idx, line in enumerate(bad_cases):
            urls = self.write_urls("group\tcase_id\tpath\n" + line, f"bad_{idx}.tsv")
            rc = perf_probe.main(["--base-url", self.base_url, "--urls", str(urls), "--preflight-only"])
            self.assertEqual(rc, 2, f"Expected preflight failure for: {line.strip()}")

    def test_keepalive_reuse_per_group_caps_nearest_rank_and_no_overwrite(self) -> None:
        urls = self.write_urls(VALID_URLS_TSV)
        out_dir = self.tmp_path / "run_s1"
        rc = perf_probe.main(
            [
                "--base-url",
                self.base_url,
                "--urls",
                str(urls),
                "--out-dir",
                str(out_dir),
                "--profile",
                "S1",
                "--per-group-requests",
                "20",
                "--warmup-rounds",
                "1",
                "--duration",
                "15",
            ]
        )
        self.assertEqual(rc, 0)

        summary = json.loads((out_dir / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["run_status"], "COMPLETED")
        # 5 screen groups * 20 requests each = 100 measure requests
        self.assertEqual(summary["overall"]["n_total"], 100)
        self.assertEqual(summary["overall"]["n_ok"], 100)
        self.assertEqual(summary["overall"]["n_err"], 0)
        self.assertEqual(summary["reconnect_count"], 0)
        # All measure requests reused the single keep-alive socket established in warmup
        self.assertEqual(summary["overall"]["reused_conn_count"], 100)
        for grp in ("home", "list", "filter", "detail", "institution"):
            self.assertEqual(summary["by_group"][grp]["n_total"], 20)
            self.assertTrue(summary["by_group"][grp]["min_sample_20_met"])
            self.assertIsNotNone(summary["by_group"][grp]["p95_ok_ms"])

        # Raw CSV has both warmup and measure rows and zero body column
        with (out_dir / "requests.csv").open(encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        self.assertEqual(sum(1 for r in rows if r["phase"] == "measure"), 100)
        self.assertGreater(sum(1 for r in rows if r["phase"] == "warmup"), 0)
        self.assertNotIn("body", rows[0])

        # Refuses to overwrite existing output directory
        rc_overwrite = perf_probe.main(
            ["--base-url", self.base_url, "--urls", str(urls), "--out-dir", str(out_dir), "--profile", "S1"]
        )
        self.assertEqual(rc_overwrite, 2)

    def test_n_under_20_omits_p95_and_reports_max_and_errors_separately(self) -> None:
        urls = self.write_urls(
            "group\tcase_id\tpath\nhome\thome_root\t/\nlist\tjobs_default\t/jobs\n"
        )
        self.state.mode = "mixed_errors"
        out_dir = self.tmp_path / "run_small"
        rc = perf_probe.main(
            [
                "--base-url",
                self.base_url,
                "--urls",
                str(urls),
                "--out-dir",
                str(out_dir),
                "--profile",
                "custom",
                "--concurrency",
                "1",
                "--max-requests",
                "25",
                "--warmup-rounds",
                "1",
                "--timeout",
                "0.12",
                "--abort-error-rate",
                "0.99",
                "--abort-max-5xx",
                "99",
                "--abort-consecutive-timeouts",
                "99",
            ]
        )
        self.assertEqual(rc, 0)
        summary = json.loads((out_dir / "summary.json").read_text(encoding="utf-8"))
        # Overall n=25 >= 20, per-group n=13 and 12 (< 20 -> p95_ok_ms must be None)
        self.assertEqual(summary["overall"]["n_total"], 25)
        self.assertEqual(summary["overall"]["errors"]["non_200"], 1)
        self.assertEqual(summary["overall"]["errors"]["timeout"], 1)
        self.assertIsNotNone(summary["overall"]["p95_ok_ms"])
        self.assertIsNotNone(summary["overall"]["p95_all_ms"])
        self.assertEqual(summary["overall"]["timeout_truncated_at_ms"], 120.0)
        self.assertGreaterEqual(summary["reconnect_count"], 1)
        self.assertIsNone(summary["by_group"]["home"]["p95_ok_ms"])
        self.assertIsNotNone(summary["by_group"]["home"]["max_ok_ms"])
        self.assertIn("n<20", (out_dir / "summary.md").read_text(encoding="utf-8"))

    def test_safety_abort_on_5xx_timeout_and_rolling_error_rate(self) -> None:
        urls = self.write_urls("group\tcase_id\tpath\nhome\thome_root\t/\nlist\tjobs_default\t/jobs\n")

        # 1) 5xx cumulative >= 3
        self.state.mode = "fail_5xx"
        self.state.call_seq = 0
        out_5xx = self.tmp_path / "abort_5xx"
        rc = perf_probe.main(
            [
                "--base-url", self.base_url, "--urls", str(urls), "--out-dir", str(out_5xx),
                "--profile", "custom", "--concurrency", "1", "--max-requests", "50",
                "--warmup-rounds", "1", "--abort-max-5xx", "3", "--abort-min-window-requests", "100",
            ]
        )
        self.assertEqual(rc, 3)
        s5 = json.loads((out_5xx / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(s5["run_status"], "ABORTED_SAFETY")
        self.assertIn("http_5xx=3>=3", s5["abort_reason"])
        self.assertTrue((out_5xx / "requests.csv").exists())

        # 2) Consecutive timeouts >= 2
        self.state.mode = "fail_timeout"
        self.state.call_seq = 0
        out_to = self.tmp_path / "abort_to"
        rc = perf_probe.main(
            [
                "--base-url", self.base_url, "--urls", str(urls), "--out-dir", str(out_to),
                "--profile", "custom", "--concurrency", "1", "--max-requests", "50",
                "--warmup-rounds", "1", "--timeout", "0.1", "--abort-consecutive-timeouts", "2",
                "--abort-min-window-requests", "100",
            ]
        )
        self.assertEqual(rc, 3)
        sto = json.loads((out_to / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(sto["run_status"], "ABORTED_SAFETY")
        self.assertIn("consecutive_timeouts=2>=2", sto["abort_reason"])

        # 3) Rolling error rate > 2% (302 redirects not followed)
        self.state.mode = "fail_redirect_rate"
        self.state.call_seq = 0
        out_rate = self.tmp_path / "abort_rate"
        rc = perf_probe.main(
            [
                "--base-url", self.base_url, "--urls", str(urls), "--out-dir", str(out_rate),
                "--profile", "custom", "--concurrency", "1", "--max-requests", "50",
                "--warmup-rounds", "1", "--abort-error-rate", "0.02", "--abort-min-window-requests", "6",
            ]
        )
        self.assertEqual(rc, 3)
        sr = json.loads((out_rate / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(sr["run_status"], "ABORTED_SAFETY")
        self.assertIn("error_rate", sr["abort_reason"])

    def test_stop_file_and_duration_cap(self) -> None:
        urls = self.write_urls("group\tcase_id\tpath\nhome\thome_root\t/\nlist\tjobs_default\t/jobs\n")
        stop_file = self.tmp_path / "probe.stop"
        self.state.mode = "slow_10ms"
        self.state.stop_file_to_create = stop_file
        self.state.stop_after_calls = 8
        out_stop = self.tmp_path / "run_stop"
        rc = perf_probe.main(
            [
                "--base-url", self.base_url, "--urls", str(urls), "--out-dir", str(out_stop),
                "--profile", "custom", "--concurrency", "1", "--duration", "30",
                "--max-requests", "200", "--warmup-rounds", "1", "--stop-file", str(stop_file),
            ]
        )
        self.assertEqual(rc, 0)
        ss = json.loads((out_stop / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(ss["run_status"], "STOPPED_BY_STOP_FILE")
        self.assertLess(ss["overall"]["n_total"], 20)

    def test_nearest_rank_math_matches_g4_probe(self) -> None:
        vals = [float(i) for i in range(1, 21)]  # 1..20 -> ceil(0.95 * 20) = 19 -> 19.0
        self.assertEqual(perf_probe.nearest_rank(vals, 95), 19.0)
        vals45 = [float(i) for i in range(1, 46)]  # ceil(0.95 * 45) = 43 -> 43.0
        self.assertEqual(perf_probe.nearest_rank(vals45, 95), 43.0)
        self.assertIsNone(perf_probe.nearest_rank(vals[:19], 95))

    def test_urls_example_tsv_parses_cleanly_with_all_five_groups(self) -> None:
        example_path = pathlib.Path(__file__).resolve().parents[1] / "perf" / "urls.example.tsv"
        cases = perf_probe.load_urls_tsv(example_path)
        groups = {c.group for c in cases}
        self.assertEqual(groups, set(perf_probe.ALLOWED_GROUPS))
        self.assertGreaterEqual(len(cases), 10)
        self.assertTrue(all(not c.path.endswith("/source") for c in cases))
        self.assertTrue(all(not c.path.startswith("/subscriptions") for c in cases))

    def test_warmup_safety_abort_5xx_timeout_and_rolling_error_rate(self) -> None:
        five_home_tsv = "\n".join(
            ["group\tcase_id\tpath"] + [f"home\th{i}\t/" for i in range(1, 6)]
        ) + "\n"
        urls = self.write_urls(five_home_tsv, "five_home.tsv")

        # 1) 5xx cumulative >= 3 during warmup stops before finishing warmup and keeps measure denominator separate
        self.state.mode = "fail_warmup_5xx"
        self.state.call_seq = 0
        self.state.preflight_count = 5
        out_5xx = self.tmp_path / "warmup_abort_5xx"
        rc = perf_probe.main(
            [
                "--base-url", self.base_url, "--urls", str(urls), "--out-dir", str(out_5xx),
                "--profile", "custom", "--concurrency", "1", "--max-requests", "20",
                "--warmup-rounds", "2", "--abort-max-5xx", "3", "--abort-min-window-requests", "100",
            ]
        )
        self.assertEqual(rc, 3)
        s5 = json.loads((out_5xx / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(s5["run_status"], "ABORTED_SAFETY")
        self.assertIn("http_5xx=3>=3", s5["abort_reason"])
        self.assertIn("phase=warmup", s5["abort_reason"])
        self.assertEqual(s5["warmup_requests"], 3)
        self.assertEqual(s5["warmup_errors"]["http_5xx"], 3)
        self.assertEqual(s5["overall"]["n_total"], 0)
        self.assertEqual(self.state.call_seq, 5 + 3)
        with (out_5xx / "requests.csv").open(encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        self.assertEqual(len(rows), 3)
        self.assertTrue(all(r["phase"] == "warmup" and r["error_kind"] == "http_5xx" for r in rows))
        self.assertTrue((out_5xx / "summary.md").exists() and (out_5xx / "conditions.md").exists())

        # 2) Consecutive timeouts >= 2 during warmup stops immediately
        self.state.mode = "fail_warmup_timeout"
        self.state.call_seq = 0
        self.state.preflight_count = 5
        out_to = self.tmp_path / "warmup_abort_to"
        rc = perf_probe.main(
            [
                "--base-url", self.base_url, "--urls", str(urls), "--out-dir", str(out_to),
                "--profile", "custom", "--concurrency", "1", "--max-requests", "20",
                "--warmup-rounds", "2", "--timeout", "0.1", "--abort-consecutive-timeouts", "2",
                "--abort-min-window-requests", "100",
            ]
        )
        self.assertEqual(rc, 3)
        sto = json.loads((out_to / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(sto["run_status"], "ABORTED_SAFETY")
        self.assertIn("consecutive_timeouts=2>=2", sto["abort_reason"])
        self.assertIn("phase=warmup", sto["abort_reason"])
        self.assertEqual(sto["warmup_requests"], 2)
        self.assertEqual(sto["warmup_errors"]["timeout"], 2)
        self.assertEqual(sto["overall"]["n_total"], 0)
        self.assertEqual(self.state.call_seq, 5 + 2)

        # 3) Rolling error rate > 2% during warmup (302 redirects, non-5xx, non-timeout)
        self.state.mode = "fail_warmup_redirect"
        self.state.call_seq = 0
        self.state.preflight_count = 5
        out_rate = self.tmp_path / "warmup_abort_rate"
        rc = perf_probe.main(
            [
                "--base-url", self.base_url, "--urls", str(urls), "--out-dir", str(out_rate),
                "--profile", "custom", "--concurrency", "1", "--max-requests", "20",
                "--warmup-rounds", "2", "--abort-error-rate", "0.02", "--abort-min-window-requests", "4",
            ]
        )
        self.assertEqual(rc, 3)
        sr = json.loads((out_rate / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(sr["run_status"], "ABORTED_SAFETY")
        self.assertIn("error_rate", sr["abort_reason"])
        self.assertIn("phase=warmup", sr["abort_reason"])
        self.assertEqual(sr["warmup_requests"], 4)
        self.assertEqual(sr["warmup_errors"]["non_200"], 4)
        self.assertEqual(sr["overall"]["n_total"], 0)

    def test_c4_warmup_abort_does_not_deadlock_barrier_and_records_inflight(self) -> None:
        # Subcase 1: All 4 workers in-flight simultaneously; 3 trigger ABORTED_SAFETY, 4th finishes & is honestly recorded, no 5th dispatched.
        urls4 = self.write_urls(
            "group\tcase_id\tpath\nhome\th1\t/\nlist\tl1\t/jobs\ndetail\td1\t/jobs/307027\ninstitution\ti1\t/institutions/C0008\n",
            "urls4.tsv",
        )
        self.state.mode = "c4_warmup_5xx_inflight"
        self.state.call_seq = 0
        self.state.preflight_count = 4
        self.state.inflight_barrier = threading.Barrier(4, timeout=2.0)
        out_c4 = self.tmp_path / "c4_warmup_inflight"
        t0 = time.monotonic()
        rc = perf_probe.main(
            [
                "--base-url", self.base_url, "--urls", str(urls4), "--out-dir", str(out_c4),
                "--profile", "S2", "--concurrency", "4", "--duration", "10", "--max-requests", "100",
                "--warmup-rounds", "3", "--abort-max-5xx", "3", "--abort-min-window-requests", "100",
            ]
        )
        elapsed = time.monotonic() - t0
        self.assertLess(elapsed, 3.0)
        self.assertEqual(rc, 3)
        sc4 = json.loads((out_c4 / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(sc4["run_status"], "ABORTED_SAFETY")
        self.assertIn("http_5xx=3>=3", sc4["abort_reason"])
        self.assertEqual(sc4["overall"]["n_total"], 0)
        # With C=4 and threshold=3, at most (C-1)=3 other workers can already have an in-flight request
        # when the 3rd 5xx triggers ABORTED_SAFETY, so total warmup requests must be in [3, 6] (far below 4*4*3=48),
        # and every request received by the server after preflight must be honestly recorded in requests.csv.
        expected_post_preflight = self.state.call_seq - 4
        self.assertGreaterEqual(sc4["warmup_requests"], 3)
        self.assertLessEqual(sc4["warmup_requests"], 3 + (4 - 1))
        self.assertEqual(sc4["warmup_requests"], expected_post_preflight)
        self.assertEqual(sc4["warmup_errors"]["http_5xx"], expected_post_preflight)
        with (out_c4 / "requests.csv").open(encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        self.assertEqual(len(rows), expected_post_preflight)
        self.assertTrue(all(r["phase"] == "warmup" for r in rows))

        # Subcase 2: One worker finishes warmup and waits at warmup_barrier while others abort -> must unblock barrier cleanly
        urls1 = self.write_urls("group\tcase_id\tpath\nhome\th1\t/\n", "urls1.tsv")
        self.state.mode = "c4_warmup_barrier_waiting_abort"
        self.state.call_seq = 0
        self.state.preflight_count = 1
        self.state.inflight_barrier = None
        out_bar = self.tmp_path / "c4_warmup_barrier"
        t0 = time.monotonic()
        rc2 = perf_probe.main(
            [
                "--base-url", self.base_url, "--urls", str(urls1), "--out-dir", str(out_bar),
                "--profile", "S2", "--concurrency", "4", "--duration", "10", "--max-requests", "100",
                "--warmup-rounds", "2", "--abort-max-5xx", "3", "--abort-min-window-requests", "100",
            ]
        )
        elapsed2 = time.monotonic() - t0
        self.assertLess(elapsed2, 3.0)
        self.assertEqual(rc2, 3)
        sbar = json.loads((out_bar / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(sbar["run_status"], "ABORTED_SAFETY")
        self.assertEqual(sbar["overall"]["n_total"], 0)
        self.assertGreaterEqual(sbar["warmup_requests"], 3)

    def test_warmup_duration_cap_prevents_unbounded_extension(self) -> None:
        urls = self.write_urls("group\tcase_id\tpath\nhome\thome_root\t/\nlist\tjobs_default\t/jobs\n")
        self.state.mode = "slow_80ms"
        self.state.call_seq = 0
        self.state.preflight_count = 2
        out_cap = self.tmp_path / "warmup_duration_cap"
        t0 = time.monotonic()
        rc = perf_probe.main(
            [
                "--base-url", self.base_url, "--urls", str(urls), "--out-dir", str(out_cap),
                "--profile", "custom", "--concurrency", "1", "--duration", "0.35",
                "--max-requests", "50", "--warmup-rounds", "10", "--timeout", "1.0",
            ]
        )
        elapsed = time.monotonic() - t0
        self.assertEqual(rc, 0)
        # Without warmup time cap, 10 rounds * 2 cases * 80ms = 1.6s + 0.35s measure (~2s).
        # With total load duration cap, warmup halts once 0.35s elapses.
        self.assertLess(elapsed, 1.2)
        sc = json.loads((out_cap / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(sc["run_status"], "COMPLETED")
        self.assertGreater(sc["warmup_requests"], 0)
        self.assertLess(sc["warmup_requests"], 15)
        self.assertEqual(sc["overall"]["n_total"], 0)
        self.assertEqual(sc["measure_elapsed_sec"], 0.0)
        self.assertGreaterEqual(sc["load_elapsed_sec"], 0.3)

    def test_sigint_stops_cleanly_promptly_and_preserves_raw_outputs(self) -> None:
        urls = self.write_urls("group\tcase_id\tpath\nhome\thome_root\t/\nlist\tjobs_default\t/jobs\n")
        self.state.mode = "slow_80ms"
        self.state.call_seq = 0
        self.state.preflight_count = 2
        out_sig = self.tmp_path / "run_sigint"

        def send_sigint() -> None:
            time.sleep(0.2)
            signal.raise_signal(signal.SIGINT)

        timer = threading.Thread(target=send_sigint, daemon=True)
        timer.start()
        t0 = time.monotonic()
        rc = perf_probe.main(
            [
                "--base-url", self.base_url, "--urls", str(urls), "--out-dir", str(out_sig),
                "--profile", "custom", "--concurrency", "1", "--duration", "10",
                "--max-requests", "200", "--warmup-rounds", "1",
            ]
        )
        elapsed = time.monotonic() - t0
        self.assertEqual(rc, 0)
        self.assertLess(elapsed, 2.0)
        ss = json.loads((out_sig / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(ss["run_status"], "STOPPED_BY_SIGNAL")
        self.assertTrue((out_sig / "requests.csv").exists())
        self.assertTrue((out_sig / "summary.md").exists())
        self.assertTrue((out_sig / "conditions.md").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
