#!/usr/bin/env python3
"""External read-only HTTP performance baseline probe for gongchae-bell (#18).

Execution contract:
  - Runs OUTSIDE the target OCI VM (e.g. fixed operator PC or external host) against public HTTPS
    (or a local HTTP/HTTPS test double during unit tests).
  - Never calls /jobs/{sn}/source or /subscriptions/*, never sends POST, never follows redirects,
    and never stores response bodies, tokens, or PII.
  - Sends fixed internal headers (X-Gongchae-Internal: 1, User-Agent: gongchae-probe/perf-18) so
    CatalogMetrics classifies load traffic as traffic="internal" instead of "user".
  - Uses one persistent HTTP/HTTPS connection per worker (closed-loop keep-alive). Closed-loop
    concurrency latencies are NOT open-loop saturation arrival-rate throughput.
  - Refuses to overwrite existing raw outputs in --out-dir.
"""
from __future__ import annotations

import argparse
import collections
import csv
import datetime as dt
import html.parser
import http.client
import json
import math
import os
import pathlib
import re
import signal
import socket
import ssl
import sys
import threading
import time
import urllib.parse
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

ALLOWED_GROUPS = ("home", "list", "filter", "detail", "institution")
ALLOWED_STATUS = {"open", "check", "closed", "all"}
ALLOWED_SORT = {"deadline", "begin"}
ALLOWED_CAREER = {"newcomer", "newonly", "career"}
DEFAULT_UA = "gongchae-probe/perf-18"
INTERNAL_HEADER_NAME = "X-Gongchae-Internal"
INTERNAL_HEADER_VALUE = "1"
RESULT_COUNT_RE = re.compile(r"검색 결과\s*<strong[^>]*>\s*(\d+)\s*</strong>\s*건")


@dataclass(frozen=True)
class UrlCase:
    group: str
    case_id: str
    path: str


class PreflightHtmlParser(html.parser.HTMLParser):
    """Minimal HTML parser extracting real Thymeleaf markers from home/jobs/detail/institution."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ids: set[str] = set()
        self.h1_texts: List[str] = []
        self._in_h1 = False
        self._h1_buf: List[str] = []
        self.links: set[str] = set()
        self.form_classes: Dict[str, str] = {}
        self.selected_options: Dict[str, List[str]] = collections.defaultdict(list)
        self._current_select_id: Optional[str] = None
        self.input_values: Dict[str, str] = {}
        self.active_pages: List[str] = []
        self._in_active_page_item = False
        self._in_active_page_link = False
        self._active_page_buf: List[str] = []

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]) -> None:
        attr_map = {k.lower(): ("" if v is None else v) for k, v in attrs}
        el_id = attr_map.get("id")
        if el_id:
            self.ids.add(el_id)
        if tag == "h1":
            self._in_h1 = True
            self._h1_buf = []
        elif tag == "a":
            href = attr_map.get("href")
            if href is not None:
                self.links.add(href)
            if self._in_active_page_item and "page-link" in attr_map.get("class", "").split():
                self._in_active_page_link = True
                self._active_page_buf = []
        elif tag == "form" and el_id:
            self.form_classes[el_id] = attr_map.get("class", "")
        elif tag == "select":
            self._current_select_id = el_id
        elif tag == "option" and self._current_select_id:
            if "selected" in attr_map:
                self.selected_options[self._current_select_id].append(attr_map.get("value", ""))
        elif tag == "input" and el_id:
            self.input_values[el_id] = attr_map.get("value", "")
        elif tag == "li":
            classes = set(attr_map.get("class", "").split())
            if "page-item" in classes and "active" in classes:
                self._in_active_page_item = True

    def handle_endtag(self, tag: str) -> None:
        if tag == "h1" and self._in_h1:
            self._in_h1 = False
            self.h1_texts.append("".join(self._h1_buf).strip())
        elif tag == "select":
            self._current_select_id = None
        elif tag == "a" and self._in_active_page_link:
            self._in_active_page_link = False
            text = "".join(self._active_page_buf).strip()
            if text:
                self.active_pages.append(text)
        elif tag == "li" and self._in_active_page_item:
            self._in_active_page_item = False

    def handle_data(self, data: str) -> None:
        if self._in_h1:
            self._h1_buf.append(data)
        if self._in_active_page_link:
            self._active_page_buf.append(data)


def utc_now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def validate_url_case(case: UrlCase) -> None:
    if case.group not in ALLOWED_GROUPS:
        raise ValueError(f"Unsupported group '{case.group}' (allowed: {', '.join(ALLOWED_GROUPS)})")
    if not case.case_id or not re.fullmatch(r"[A-Za-z0-9._-]+", case.case_id):
        raise ValueError(f"Invalid case_id '{case.case_id}'")
    if not case.path.startswith("/"):
        raise ValueError(f"Path must start with '/': {case.path}")

    parsed = urllib.parse.urlsplit(case.path)
    if parsed.scheme or parsed.netloc:
        raise ValueError(f"Path must be relative, not full URL: {case.path}")
    route = parsed.path
    if route.endswith("/source"):
        raise ValueError(f"Forbidden external redirect path in manifest: {case.path}")
    if route == "/subscriptions" or route.startswith("/subscriptions/"):
        raise ValueError(f"Forbidden subscription path in manifest: {case.path}")

    if case.group == "home" and route != "/":
        raise ValueError(f"home group must target '/': {case.path}")
    if case.group in ("list", "filter") and route != "/jobs":
        raise ValueError(f"{case.group} group must target '/jobs': {case.path}")
    if case.group == "detail" and not re.fullmatch(r"/jobs/\d+", route):
        raise ValueError(f"detail group must target '/jobs/<digits>': {case.path}")
    if case.group == "institution" and not re.fullmatch(r"/institutions/[A-Za-z0-9_-]+", route):
        raise ValueError(f"institution group must target '/institutions/<code>': {case.path}")

    if route == "/jobs":
        qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        for key, values in qs.items():
            if len(values) != 1:
                raise ValueError(f"Duplicate query key '{key}' in {case.path}")
            val = values[0]
            if key == "status":
                if val not in ALLOWED_STATUS:
                    raise ValueError(
                        f"Invalid status '{val}' in {case.path} (exact lowercase allowed: {sorted(ALLOWED_STATUS)})"
                    )
            elif key == "sort":
                if val not in ALLOWED_SORT:
                    raise ValueError(
                        f"Invalid sort '{val}' in {case.path} (exact lowercase allowed: {sorted(ALLOWED_SORT)})"
                    )
            elif key == "career":
                if val not in ALLOWED_CAREER:
                    raise ValueError(
                        f"Invalid career '{val}' in {case.path} (exact lowercase allowed: {sorted(ALLOWED_CAREER)})"
                    )
            elif key == "page":
                if not val.isdigit() or int(val) < 1:
                    raise ValueError(f"Invalid page '{val}' in {case.path}")
            elif key == "q":
                if not val.strip() or len(val.strip()) > 100:
                    raise ValueError(f"Invalid q '{val}' in {case.path}")
            elif key in ("ncs", "region", "hire"):
                if not val.strip():
                    raise ValueError(f"Empty value for '{key}' in {case.path}")
            else:
                raise ValueError(f"Unsupported /jobs query key '{key}' in {case.path}")


def load_urls_tsv(path: pathlib.Path) -> List[UrlCase]:
    cases: List[UrlCase] = []
    seen_ids: set[Tuple[str, str]] = set()
    with path.open("r", encoding="utf-8") as fh:
        for line_no, raw in enumerate(fh, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 3:
                raise ValueError(f"{path}:{line_no}: expected 3 tab-separated columns (group, case_id, path)")
            group, case_id, req_path = (p.strip() for p in parts)
            if not cases and group.lower() == "group" and case_id.lower() in ("case", "case_id"):
                continue
            case = UrlCase(group=group, case_id=case_id, path=req_path)
            validate_url_case(case)
            key = (case.group, case.case_id)
            if key in seen_ids:
                raise ValueError(f"{path}:{line_no}: duplicate (group, case_id) {key}")
            seen_ids.add(key)
            cases.append(case)
    if not cases:
        raise ValueError(f"{path}: no URL cases found")
    return cases


def build_round_robin_schedule(cases: List[UrlCase]) -> List[UrlCase]:
    """Interleave screen groups deterministically so every group advances evenly."""
    by_group: Dict[str, List[UrlCase]] = collections.OrderedDict()
    for grp in ALLOWED_GROUPS:
        grp_cases = [c for c in cases if c.group == grp]
        if grp_cases:
            by_group[grp] = grp_cases
    groups = list(by_group.keys())
    max_len = max(len(v) for v in by_group.values())
    # LCM-free bounded cycle: rotate across all present groups, advancing each group's case index.
    # Length = len(groups) * max_len guarantees every case appears at least once and groups stay balanced.
    schedule: List[UrlCase] = []
    indices = {g: 0 for g in groups}
    for _ in range(max_len):
        for g in groups:
            g_cases = by_group[g]
            schedule.append(g_cases[indices[g] % len(g_cases)])
            indices[g] += 1
    return schedule


def make_connection(
    parsed_base: urllib.parse.SplitResult,
    timeout: float,
    ssl_context: Optional[ssl.SSLContext],
) -> http.client.HTTPConnection:
    host = parsed_base.hostname
    if not host:
        raise ValueError("Base URL missing hostname")
    port = parsed_base.port
    if parsed_base.scheme == "https":
        return http.client.HTTPSConnection(host, port or 443, timeout=timeout, context=ssl_context)
    if parsed_base.scheme == "http":
        return http.client.HTTPConnection(host, port or 80, timeout=timeout)
    raise ValueError(f"Unsupported base URL scheme: {parsed_base.scheme}")


def encode_request_target(path: str) -> str:
    """Percent-encode non-ASCII characters in path/query for HTTP/1.1 latin-1 wire encoding."""
    parsed = urllib.parse.urlsplit(path)
    enc_path = urllib.parse.quote(urllib.parse.unquote(parsed.path), safe="/")
    if not parsed.query:
        return enc_path
    pairs = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
    enc_query = urllib.parse.urlencode(pairs, safe="")
    return f"{enc_path}?{enc_query}"


def request_once(
    conn: http.client.HTTPConnection,
    path: str,
    headers: Dict[str, str],
    keep_body: bool = False,
) -> Tuple[int, float, int, int, str, Optional[str], bool]:
    """Execute one GET without retries or redirects.

    Returns: (status, elapsed_ms, bytes_read, reused_conn, error_kind, body_text_or_None, should_close)
    """
    wire_path = encode_request_target(path)
    reused = 1 if conn.sock is not None else 0
    t0 = time.monotonic()
    try:
        conn.request("GET", wire_path, headers=headers)
        resp = conn.getresponse()
        body = resp.read()
        elapsed_ms = (time.monotonic() - t0) * 1000.0
        status = resp.status
        will_close = bool(resp.will_close or (resp.getheader("Connection") or "").lower() == "close")
        if status == 200:
            err_kind = "none"
        elif 500 <= status <= 599:
            err_kind = "http_5xx"
        else:
            err_kind = "non_200"
        body_text = body.decode("utf-8", errors="replace") if keep_body else None
        return status, elapsed_ms, len(body), reused, err_kind, body_text, will_close
    except (socket.timeout, TimeoutError):
        elapsed_ms = (time.monotonic() - t0) * 1000.0
        return 0, elapsed_ms, 0, reused, "timeout", None, True
    except (ssl.SSLError, OSError, http.client.HTTPException):
        elapsed_ms = (time.monotonic() - t0) * 1000.0
        return 0, elapsed_ms, 0, reused, "connect_tls", None, True


def verify_preflight_response(case: UrlCase, status: int, body: str, allow_empty_query: bool) -> None:
    if status != 200:
        raise ValueError(f"Preflight failed for {case.group}/{case.case_id} ({case.path}): expected HTTP 200, got {status}")
    parser = PreflightHtmlParser()
    parser.feed(body)

    if case.group == "home":
        if not any(parser.h1_texts) or "closing-heading" not in parser.ids or "today-heading" not in parser.ids:
            raise ValueError(f"Preflight home markers missing for {case.path}")
        return

    if case.group == "detail":
        if not any(parser.h1_texts) or "/jobs" not in parser.links:
            raise ValueError(f"Preflight detail markers missing for {case.path}")
        return

    if case.group == "institution":
        if not any(parser.h1_texts) or "yearly-recruit-heading" not in parser.ids or "history-heading" not in parser.ids:
            raise ValueError(f"Preflight institution markers missing for {case.path}")
        return

    # list / filter on /jobs
    if "jobs-filter-panel" not in parser.ids:
        raise ValueError(f"Preflight /jobs missing #jobs-filter-panel for {case.path}")
    m = RESULT_COUNT_RE.search(body)
    if not m:
        raise ValueError(f"Preflight /jobs missing result count marker for {case.path}")
    result_count = int(m.group(1))

    parsed = urllib.parse.urlsplit(case.path)
    qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=False)

    select_map = {
        "ncs": "filter-ncs",
        "region": "filter-region",
        "hire": "filter-hire",
        "career": "filter-career",
        "status": "filter-status",
        "sort": "filter-sort",
    }
    has_active_filter = False
    for param, select_id in select_map.items():
        if param in qs:
            expected_val = qs[param][0]
            actual_selected = parser.selected_options.get(select_id, [])
            if expected_val not in actual_selected:
                raise ValueError(
                    f"Preflight filter '{param}={expected_val}' was NOT applied in HTML for {case.path} "
                    f"(selected={actual_selected})"
                )
            if (param == "status" and expected_val != "open") or (param == "sort" and expected_val != "deadline") or param in ("ncs", "region", "hire", "career"):
                has_active_filter = True

    if "q" in qs:
        expected_q = qs["q"][0].strip()
        actual_q = parser.input_values.get("filter-q", "")
        if actual_q != expected_q:
            raise ValueError(
                f"Preflight filter 'q={expected_q}' was NOT reflected in #filter-q for {case.path} (got '{actual_q}')"
            )
        if not allow_empty_query and result_count <= 0:
            raise ValueError(
                f"Preflight filter 'q={expected_q}' returned 0 results for {case.path}; choose a matching keyword"
            )
        has_active_filter = True

    if has_active_filter:
        form_cls = set(parser.form_classes.get("jobs-filter-panel", "").split())
        if "d-none" in form_cls:
            raise ValueError(
                f"Preflight expected active filter panel (without d-none) for {case.path}"
            )

    if "page" in qs:
        expected_page = qs["page"][0]
        if int(expected_page) > 1:
            if expected_page not in parser.active_pages:
                raise ValueError(
                    f"Preflight expected active page {expected_page} for {case.path} "
                    f"(actual active_pages={parser.active_pages}; page may have been clamped)"
                )


def run_preflight(
    parsed_base: urllib.parse.SplitResult,
    cases: List[UrlCase],
    headers: Dict[str, str],
    timeout: float,
    ssl_context: Optional[ssl.SSLContext],
    allow_empty_query: bool,
) -> Dict[str, object]:
    conn = make_connection(parsed_base, timeout, ssl_context)
    rtts: List[float] = []
    checked: List[Dict[str, object]] = []
    try:
        for case in cases:
            status, elapsed_ms, nbytes, reused, err_kind, body, should_close = request_once(
                conn, case.path, headers, keep_body=True
            )
            if should_close:
                conn.close()
            if err_kind != "none" or body is None:
                raise ValueError(
                    f"Preflight request error for {case.group}/{case.case_id} ({case.path}): "
                    f"status={status} error_kind={err_kind}"
                )
            verify_preflight_response(case, status, body, allow_empty_query=allow_empty_query)
            rtts.append(elapsed_ms)
            checked.append(
                {
                    "group": case.group,
                    "case_id": case.case_id,
                    "path": case.path,
                    "status": status,
                    "elapsed_ms": round(elapsed_ms, 2),
                    "bytes_read": nbytes,
                    "reused_conn": reused,
                }
            )
    finally:
        conn.close()

    rtts_sorted = sorted(rtts)
    return {
        "checked_count": len(checked),
        "min_rtt_ms": round(rtts_sorted[0], 2),
        "median_rtt_ms": round(rtts_sorted[len(rtts_sorted) // 2], 2),
        "max_rtt_ms": round(rtts_sorted[-1], 2),
        "cases": checked,
    }


def nearest_rank(sorted_vals: List[float], percentile: int) -> Optional[float]:
    n = len(sorted_vals)
    if n < 20:
        return None
    idx = math.ceil((percentile / 100.0) * n) - 1
    idx = max(0, min(idx, n - 1))
    return round(sorted_vals[idx], 2)


def summarize_rows(rows: List[Dict[str, object]], timeout_sec: float) -> Dict[str, object]:
    n_total = len(rows)
    ok_lat = sorted(float(r["elapsed_ms"]) for r in rows if r["error_kind"] == "none")
    all_lat = sorted(float(r["elapsed_ms"]) for r in rows)
    n_ok = len(ok_lat)
    n_err = n_total - n_ok
    err_counts = {
        "http_5xx": sum(1 for r in rows if r["error_kind"] == "http_5xx"),
        "non_200": sum(1 for r in rows if r["error_kind"] == "non_200"),
        "timeout": sum(1 for r in rows if r["error_kind"] == "timeout"),
        "connect_tls": sum(1 for r in rows if r["error_kind"] == "connect_tls"),
    }
    reused_count = sum(1 for r in rows if int(r["reused_conn"]) == 1)
    out: Dict[str, object] = {
        "n_total": n_total,
        "n_ok": n_ok,
        "n_err": n_err,
        "error_rate": round(n_err / n_total, 6) if n_total > 0 else 0.0,
        "errors": err_counts,
        "min_sample_20_met": n_total >= 20,
        "reused_conn_count": reused_count,
        "new_conn_count": n_total - reused_count,
        "p50_ok_ms": nearest_rank(ok_lat, 50),
        "p95_ok_ms": nearest_rank(ok_lat, 95),
        "p99_ok_ms": nearest_rank(ok_lat, 99),
        "max_ok_ms": round(ok_lat[-1], 2) if ok_lat else None,
        "max_all_ms": round(all_lat[-1], 2) if all_lat else None,
    }
    if n_err > 0:
        out["p95_all_ms"] = nearest_rank(all_lat, 95)
        out["timeout_truncated_at_ms"] = round(timeout_sec * 1000.0, 2) if err_counts["timeout"] > 0 else None
    return out


def format_latency_cell(stats: Dict[str, object], key: str, max_key: str, n_key: str) -> str:
    n = int(stats[n_key])
    val = stats.get(key)
    mx = stats.get(max_key)
    if n == 0:
        return "-"
    if val is None:
        return f"n={n}, max={mx}ms (n<20)"
    return f"{val}ms"


def render_summary_md(summary: Dict[str, object]) -> str:
    lines = [
        "# 성능 측정 요약 (#18)",
        "",
        f"- 상태: **{summary['run_status']}**"
        + (f" (`{summary['abort_reason']}`)" if summary.get("abort_reason") else ""),
        f"- 시작/종료(UTC): `{summary['started_utc']}` ~ `{summary['finished_utc']}` (측정 {summary['measure_elapsed_sec']}s)",
        f"- 방식: closed-loop keep-alive (`concurrency={summary['config']['concurrency']}`, "
        f"`reconnect_count={summary['reconnect_count']}`, `reused_conn={summary['overall']['reused_conn_count']}/{summary['overall']['n_total']}`)",
        "- 주의: closed-loop 동시성 응답 시간이며 포화 상태 open-loop 도착률 성능이 아니다. 타임아웃 경과 시간은 타임아웃 상한에서 잘린 관측이다.",
        "",
        "## 화면군별 집계 (measure 단계, nearest-rank)",
        "",
        "| 화면군 | n_total | n_ok | 오류율 | n>=20 | 성공 p50 | 성공 p95 | 성공 max | 전체 p95 (오류 시) | 오류 내역 (5xx/non200/to/conn) |",
        "|---|---:|---:|---:|---|---:|---:|---:|---:|---|",
    ]
    for grp, st in summary["by_group"].items():
        errs = st["errors"]
        all_p95 = "-"
        if st["n_err"] > 0:
            all_p95 = format_latency_cell(st, "p95_all_ms", "max_all_ms", "n_total")
            if st.get("timeout_truncated_at_ms"):
                all_p95 += f" (to>={int(st['timeout_truncated_at_ms'])}ms)"
        lines.append(
            f"| `{grp}` | {st['n_total']} | {st['n_ok']} | {st['error_rate']*100:.2f}% | "
            f"{'Y' if st['min_sample_20_met'] else 'N'} | "
            f"{format_latency_cell(st, 'p50_ok_ms', 'max_ok_ms', 'n_ok')} | "
            f"{format_latency_cell(st, 'p95_ok_ms', 'max_ok_ms', 'n_ok')} | "
            f"{st['max_ok_ms'] if st['max_ok_ms'] is not None else '-'}ms | "
            f"{all_p95} | "
            f"{errs['http_5xx']}/{errs['non_200']}/{errs['timeout']}/{errs['connect_tls']} |"
        )

    lines += [
        "",
        "## 케이스별 집계",
        "",
        "| 화면군 | case_id | n_total | n_ok | 오류율 | 성공 p95 | 성공 max |",
        "|---|---|---:|---:|---:|---:|---:|",
    ]
    for key, st in summary["by_case"].items():
        grp, cid = key.split("/", 1)
        lines.append(
            f"| `{grp}` | `{cid}` | {st['n_total']} | {st['n_ok']} | {st['error_rate']*100:.2f}% | "
            f"{format_latency_cell(st, 'p95_ok_ms', 'max_ok_ms', 'n_ok')} | "
            f"{st['max_ok_ms'] if st['max_ok_ms'] is not None else '-'}ms |"
        )
    return "\n".join(lines) + "\n"


def render_conditions_md(summary: Dict[str, object], cases: List[UrlCase], meta: Dict[str, str]) -> str:
    lines = [
        "# 성능 측정 조건 (#18)",
        "",
        "| 항목 | 값 |",
        "|---|---|",
        f"| 실행 상태 | `{summary['run_status']}` |",
        f"| 중단 사유 | `{summary.get('abort_reason') or 'none'}` |",
        f"| 대상 Base URL | `{summary['config']['base_url']}` |",
        f"| 시작 / 종료 (UTC) | `{summary['started_utc']}` / `{summary['finished_utc']}` |",
        f"| 프로파일 / 동시성 | `{summary['config']['profile']}` / `C={summary['config']['concurrency']}` |",
        f"| 시간 / 요청 상한 | `duration={summary['config']['duration_sec']}s`, `max_requests={summary['config']['max_requests']}`, `per_group_requests={summary['config']['per_group_requests']}` |",
        f"| 타임아웃 / 워밍업 | `timeout={summary['config']['timeout_sec']}s`, `warmup_rounds={summary['config']['warmup_rounds']}` |",
        f"| 고정 헤더 | `{INTERNAL_HEADER_NAME}: {INTERNAL_HEADER_VALUE}`, `User-Agent: {DEFAULT_UA}` |",
        f"| 사전 검증 RTT (min/med/max) | `{summary['preflight']['min_rtt_ms']} / {summary['preflight']['median_rtt_ms']} / {summary['preflight']['max_rtt_ms']} ms` |",
        f"| 클라이언트 위치 | `{meta['client_location']}` |",
        f"| 앱 SHA / 이미지 digest | `{meta['app_sha']}` / `{meta['image_digest']}` |",
        f"| 활성 색 | `{meta['active_color']}` |",
        f"| 데이터 건수 / 플래그 | `posting={meta['posting_count']}`, `COLLECTOR_ENABLED={meta['collector_enabled']}`, `ALERTS_ENABLED={meta['alerts_enabled']}` |",
        "",
        "## 고정 URL 목록",
        "",
        "| group | case_id | path |",
        "|---|---|---|",
    ]
    for c in cases:
        lines.append(f"| `{c.group}` | `{c.case_id}` | `{c.path}` |")
    return "\n".join(lines) + "\n"


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="External read-only performance probe for gongchae-bell (#18)")
    p.add_argument("--base-url", required=True, help="Target base URL (e.g. https://gongchae.enjoylcjworld.com)")
    p.add_argument("--urls", required=True, type=pathlib.Path, help="Path to fixed urls.tsv manifest")
    p.add_argument("--out-dir", type=pathlib.Path, help="Output directory (refuses to overwrite existing raw data)")
    p.add_argument("--profile", choices=("S1", "S2", "custom"), default="S1", help="Preset load profile (S1 or S2)")
    p.add_argument("--concurrency", type=int, default=None, help="Worker thread count (S1=1, S2=4)")
    p.add_argument("--duration", type=float, default=None, help="Max measure duration seconds (S1=180, S2=120)")
    p.add_argument("--max-requests", type=int, default=None, help="Max total measure requests (S2=1500)")
    p.add_argument("--per-group-requests", type=int, default=None, help="Target measure requests per screen group (S1=40)")
    p.add_argument("--warmup-rounds", type=int, default=1, help="Warmup rounds of the URL schedule per worker (excluded from measure)")
    p.add_argument("--timeout", type=float, default=10.0, help="Per-request read/connect timeout in seconds (default 10)")
    p.add_argument("--stop-file", type=pathlib.Path, default=None, help="Stop cleanly when this file appears")
    p.add_argument("--preflight-only", action="store_true", help="Run preflight validation only and exit")
    p.add_argument("--allow-empty-query", action="store_true", help="Allow q= filter with 0 results during preflight")
    p.add_argument("--abort-error-rate", type=float, default=0.02, help="Rolling error rate safety abort threshold (default 0.02)")
    p.add_argument("--abort-window-sec", type=float, default=30.0, help="Rolling error rate window seconds (default 30)")
    p.add_argument("--abort-min-window-requests", type=int, default=10, help="Min requests in rolling window before rate abort fires (default 10)")
    p.add_argument("--abort-max-5xx", type=int, default=3, help="Cumulative HTTP 5xx safety abort threshold (default 3)")
    p.add_argument("--abort-consecutive-timeouts", type=int, default=2, help="Consecutive timeout safety abort threshold (default 2)")
    p.add_argument("--client-location", default="미확인", help="Recorded client location label")
    p.add_argument("--app-sha", default="미확인", help="Recorded deployed app SHA")
    p.add_argument("--image-digest", default="미확인", help="Recorded image digest")
    p.add_argument("--active-color", default="미확인", help="Recorded active color (blue/green)")
    p.add_argument("--posting-count", default="미확인", help="Recorded DB posting count before run")
    p.add_argument("--collector-enabled", default="미확인", help="Recorded COLLECTOR_ENABLED flag")
    p.add_argument("--alerts-enabled", default="미확인", help="Recorded ALERTS_ENABLED flag")
    return p.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    parsed_base = urllib.parse.urlsplit(args.base_url)
    if parsed_base.scheme not in ("http", "https") or not parsed_base.hostname:
        print(f"ERROR: invalid --base-url '{args.base_url}'", file=sys.stderr)
        return 2

    try:
        cases = load_urls_tsv(args.urls)
    except Exception as exc:
        print(f"ERROR: manifest validation failed: {exc}", file=sys.stderr)
        return 2

    if args.profile == "S1":
        concurrency = args.concurrency if args.concurrency is not None else 1
        duration_sec = args.duration if args.duration is not None else 180.0
        per_group_requests = args.per_group_requests if args.per_group_requests is not None else 40
        max_requests = args.max_requests
    elif args.profile == "S2":
        concurrency = args.concurrency if args.concurrency is not None else 4
        duration_sec = args.duration if args.duration is not None else 120.0
        per_group_requests = args.per_group_requests
        max_requests = args.max_requests if args.max_requests is not None else 1500
    else:
        concurrency = args.concurrency if args.concurrency is not None else 1
        duration_sec = args.duration if args.duration is not None else 60.0
        per_group_requests = args.per_group_requests
        max_requests = args.max_requests

    if concurrency < 1 or duration_sec <= 0 or args.timeout <= 0:
        print("ERROR: concurrency, duration, and timeout must be positive", file=sys.stderr)
        return 2

    if not args.preflight_only:
        if args.out_dir is None:
            print("ERROR: --out-dir is required unless --preflight-only is set", file=sys.stderr)
            return 2
        if args.out_dir.exists() and any(
            (args.out_dir / name).exists() for name in ("requests.csv", "summary.json", "summary.md", "conditions.md")
        ):
            print(f"ERROR: OUTPUT_EXISTS refusing to overwrite existing results in {args.out_dir}", file=sys.stderr)
            return 2

    headers = {
        INTERNAL_HEADER_NAME: INTERNAL_HEADER_VALUE,
        "User-Agent": DEFAULT_UA,
        "Connection": "keep-alive",
        "Accept": "text/html,application/xhtml+xml",
    }
    ssl_context = ssl.create_default_context() if parsed_base.scheme == "https" else None

    try:
        preflight = run_preflight(
            parsed_base, cases, headers, args.timeout, ssl_context, allow_empty_query=args.allow_empty_query
        )
    except Exception as exc:
        print(f"ERROR: PREFLIGHT_FAILED {exc}", file=sys.stderr)
        return 2

    if args.preflight_only:
        print(json.dumps({"status": "PREFLIGHT_OK", "preflight": preflight}, ensure_ascii=False, indent=2))
        return 0

    args.out_dir.mkdir(parents=True, exist_ok=True)
    schedule = build_round_robin_schedule(cases)
    present_groups = sorted({c.group for c in cases}, key=lambda g: ALLOWED_GROUPS.index(g))

    stop_event = threading.Event()
    state_lock = threading.Lock()
    run_status = "COMPLETED"
    abort_reason = ""
    raw_rows: List[Dict[str, object]] = []
    measure_rows: List[Dict[str, object]] = []
    group_counts: Dict[str, int] = {g: 0 for g in present_groups}
    measure_dispatched = 0
    rolling_window: collections.deque[Tuple[float, bool]] = collections.deque()
    cum_5xx = 0
    consecutive_timeouts = 0
    reconnect_count = 0
    schedule_cursor = 0

    def trigger_stop(status: str, reason: str) -> None:
        nonlocal run_status, abort_reason
        if not stop_event.is_set():
            run_status = status
            abort_reason = reason
            stop_event.set()

    def handle_signal(signum: int, _frame: object) -> None:
        with state_lock:
            trigger_stop("STOPPED_BY_SIGNAL", f"signal={signum}")

    prev_sigint = signal.signal(signal.SIGINT, handle_signal)
    prev_sigterm = None
    if hasattr(signal, "SIGTERM"):
        prev_sigterm = signal.signal(signal.SIGTERM, handle_signal)

    started_utc = utc_now_iso()
    measure_start_mono: Optional[float] = None
    measure_deadline_mono: Optional[float] = None
    warmup_barrier = threading.Barrier(concurrency)

    def next_measure_case() -> Optional[UrlCase]:
        nonlocal measure_dispatched, schedule_cursor
        if stop_event.is_set():
            return None
        if args.stop_file is not None and args.stop_file.exists():
            trigger_stop("STOPPED_BY_STOP_FILE", f"stop_file={args.stop_file}")
            return None
        now_m = time.monotonic()
        if measure_deadline_mono is not None and now_m >= measure_deadline_mono:
            return None
        if max_requests is not None and measure_dispatched >= max_requests:
            return None
        if per_group_requests is not None:
            if all(group_counts[g] >= per_group_requests for g in present_groups):
                return None
            # Pick next group in schedule that still needs requests.
            for _ in range(len(schedule)):
                cand = schedule[schedule_cursor % len(schedule)]
                schedule_cursor += 1
                if group_counts[cand.group] < per_group_requests:
                    group_counts[cand.group] += 1
                    measure_dispatched += 1
                    return cand
            return None
        cand = schedule[schedule_cursor % len(schedule)]
        schedule_cursor += 1
        group_counts[cand.group] += 1
        measure_dispatched += 1
        return cand

    def worker_fn(worker_id: int) -> None:
        nonlocal cum_5xx, consecutive_timeouts, reconnect_count, measure_start_mono, measure_deadline_mono
        conn = make_connection(parsed_base, args.timeout, ssl_context)
        had_prior_conn = False
        passed_barrier = False
        try:
            # Phase 1: warmup
            for _ in range(max(0, args.warmup_rounds)):
                for case in schedule:
                    if stop_event.is_set():
                        break
                    if args.stop_file is not None and args.stop_file.exists():
                        with state_lock:
                            trigger_stop("STOPPED_BY_STOP_FILE", f"stop_file={args.stop_file}")
                        break
                    ts = utc_now_iso()
                    was_closed = conn.sock is None
                    status, elapsed_ms, nbytes, reused, err_kind, _, should_close = request_once(
                        conn, case.path, headers, keep_body=False
                    )
                    if should_close:
                        conn.close()
                    with state_lock:
                        if had_prior_conn and was_closed:
                            reconnect_count += 1
                        had_prior_conn = True
                        raw_rows.append(
                            {
                                "ts_start_utc": ts,
                                "phase": "warmup",
                                "worker": worker_id,
                                "group": case.group,
                                "case_id": case.case_id,
                                "path": case.path,
                                "status": status,
                                "elapsed_ms": round(elapsed_ms, 2),
                                "bytes_read": nbytes,
                                "reused_conn": reused,
                                "error_kind": err_kind,
                            }
                        )
                if stop_event.is_set():
                    break

            try:
                warmup_barrier.wait()
                passed_barrier = True
            except threading.BrokenBarrierError:
                return

            with state_lock:
                if measure_start_mono is None:
                    measure_start_mono = time.monotonic()
                    measure_deadline_mono = measure_start_mono + duration_sec

            # Phase 2: measure
            while True:
                with state_lock:
                    case = next_measure_case()
                if case is None:
                    break
                ts = utc_now_iso()
                was_closed = conn.sock is None
                status, elapsed_ms, nbytes, reused, err_kind, _, should_close = request_once(
                    conn, case.path, headers, keep_body=False
                )
                if should_close:
                    conn.close()
                now_m = time.monotonic()
                row = {
                    "ts_start_utc": ts,
                    "phase": "measure",
                    "worker": worker_id,
                    "group": case.group,
                    "case_id": case.case_id,
                    "path": case.path,
                    "status": status,
                    "elapsed_ms": round(elapsed_ms, 2),
                    "bytes_read": nbytes,
                    "reused_conn": reused,
                    "error_kind": err_kind,
                }
                with state_lock:
                    if had_prior_conn and was_closed:
                        reconnect_count += 1
                    had_prior_conn = True
                    raw_rows.append(row)
                    measure_rows.append(row)

                    is_err = err_kind != "none"
                    if err_kind == "http_5xx":
                        cum_5xx += 1
                    if err_kind == "timeout":
                        consecutive_timeouts += 1
                    else:
                        consecutive_timeouts = 0

                    rolling_window.append((now_m, is_err))
                    cutoff = now_m - args.abort_window_sec
                    while rolling_window and rolling_window[0][0] < cutoff:
                        rolling_window.popleft()

                    if cum_5xx >= args.abort_max_5xx:
                        trigger_stop("ABORTED_SAFETY", f"http_5xx={cum_5xx}>={args.abort_max_5xx}")
                    elif consecutive_timeouts >= args.abort_consecutive_timeouts:
                        trigger_stop(
                            "ABORTED_SAFETY",
                            f"consecutive_timeouts={consecutive_timeouts}>={args.abort_consecutive_timeouts}",
                        )
                    elif len(rolling_window) >= args.abort_min_window_requests:
                        win_errs = sum(1 for _, e in rolling_window if e)
                        win_rate = win_errs / len(rolling_window)
                        if win_rate > args.abort_error_rate:
                            trigger_stop(
                                "ABORTED_SAFETY",
                                f"rolling_{int(args.abort_window_sec)}s_error_rate={win_rate:.4f}>{args.abort_error_rate}",
                            )
        finally:
            if not passed_barrier:
                warmup_barrier.abort()
            conn.close()

    threads = [threading.Thread(target=worker_fn, args=(i + 1,), daemon=True) for i in range(concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    signal.signal(signal.SIGINT, prev_sigint)
    if prev_sigterm is not None:
        signal.signal(signal.SIGTERM, prev_sigterm)

    finished_utc = utc_now_iso()
    measure_elapsed_sec = round(time.monotonic() - measure_start_mono, 2) if measure_start_mono is not None else 0.0

    # Write raw CSV
    csv_path = args.out_dir / "requests.csv"
    fieldnames = [
        "ts_start_utc",
        "phase",
        "worker",
        "group",
        "case_id",
        "path",
        "status",
        "elapsed_ms",
        "bytes_read",
        "reused_conn",
        "error_kind",
    ]
    with csv_path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(raw_rows)

    by_group: Dict[str, Dict[str, object]] = {}
    for g in present_groups:
        by_group[g] = summarize_rows([r for r in measure_rows if r["group"] == g], args.timeout)

    by_case: Dict[str, Dict[str, object]] = {}
    for c in cases:
        key = f"{c.group}/{c.case_id}"
        by_case[key] = summarize_rows(
            [r for r in measure_rows if r["group"] == c.group and r["case_id"] == c.case_id], args.timeout
        )

    summary = {
        "run_status": run_status,
        "abort_reason": abort_reason or None,
        "started_utc": started_utc,
        "finished_utc": finished_utc,
        "measure_elapsed_sec": measure_elapsed_sec,
        "reconnect_count": reconnect_count,
        "warmup_requests": sum(1 for r in raw_rows if r["phase"] == "warmup"),
        "config": {
            "base_url": args.base_url,
            "profile": args.profile,
            "concurrency": concurrency,
            "duration_sec": duration_sec,
            "max_requests": max_requests,
            "per_group_requests": per_group_requests,
            "warmup_rounds": args.warmup_rounds,
            "timeout_sec": args.timeout,
        },
        "preflight": preflight,
        "overall": summarize_rows(measure_rows, args.timeout),
        "by_group": by_group,
        "by_case": by_case,
    }

    (args.out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.out_dir / "summary.md").write_text(render_summary_md(summary), encoding="utf-8")
    meta = {
        "client_location": args.client_location,
        "app_sha": args.app_sha,
        "image_digest": args.image_digest,
        "active_color": args.active_color,
        "posting_count": args.posting_count,
        "collector_enabled": args.collector_enabled,
        "alerts_enabled": args.alerts_enabled,
    }
    (args.out_dir / "conditions.md").write_text(render_conditions_md(summary, cases, meta), encoding="utf-8")

    print(
        f"PERF_PROBE_{run_status} total={summary['overall']['n_total']} ok={summary['overall']['n_ok']} "
        f"err={summary['overall']['n_err']} reconnects={reconnect_count} out={args.out_dir}"
    )
    if run_status == "ABORTED_SAFETY":
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
