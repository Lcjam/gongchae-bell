#!/usr/bin/env python3
"""docs/의 PRD, 설계, ADR을 검수용 단일 페이지 review/index.html로 만든다. review/는 커밋하지 않는다.

원본은 .md다. 문서를 고치면 다시 실행한다: python scripts/build-review-html.py
마크다운 변환은 npx로 실행하는 marked를 쓰므로 프로젝트에 의존성이 추가되지 않는다.
"""
import datetime
import glob
import html
import os
import posixpath
import re
import shutil
import subprocess

os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = "review/index.html"
GROUPS = [
    ("기획", ["docs/prd.md"]),
    ("설계", [f"docs/design/{n}.md" for n in ("architecture", "erd", "routes", "wireframes")]),
    ("ADR", ["docs/adr/README.md"] + sorted(p.replace("\\", "/") for p in glob.glob("docs/adr/0*.md"))),
]
sources = [p for _, paths in GROUPS for p in paths]


def anchor(path):
    return re.sub(r"[^a-z0-9]+", "-", path.removeprefix("docs/").removesuffix(".md").lower())


ids = {p: anchor(p) for p in sources}
texts = {p: open(p, encoding="utf-8").read() for p in sources}
titles = {p: re.search(r"^# (.+)$", texts[p], re.M).group(1) for p in sources}

joined = "".join(f"\n\n<!--DOC:{i}-->\n\n{texts[p]}" for i, p in enumerate(sources))
rendered = subprocess.run(
    [shutil.which("npx"), "-y", "marked@15.0.12"], input=joined.encode("utf-8"), capture_output=True, check=True
).stdout.decode("utf-8")
parts = re.split(r"<!--DOC:\d+-->", rendered)[1:]
assert len(parts) == len(sources), "문서 구분 표시가 변환 중에 사라졌다"


def fix_links(body, src):
    def repl(m):
        href = m.group(1)
        if re.match(r"[a-z]+:|#", href):
            return m.group(0)
        target = posixpath.normpath(posixpath.join(posixpath.dirname(src), href.partition("#")[0]))
        if os.path.isdir(target):
            target += "/README.md"
        assert os.path.exists(target), f"{src}: 깨진 링크 {href}"
        new = f"#{ids[target]}" if target in ids else posixpath.relpath(target, posixpath.dirname(OUT))
        return f'href="{new}"'

    return re.sub(r'href="([^"]+)"', repl, body)


sections, nav = [], []
for path, body in zip(sources, parts):
    body = fix_links(body, path)
    body = re.sub(r'<pre><code class="language-mermaid">(.*?)</code></pre>', r'<pre class="mermaid">\1</pre>', body, flags=re.S)
    body = body.replace("<table>", '<div class="table"><table>').replace("</table>", "</table></div>")
    assert "<h1>" in body, f"{path}: 제목이 없다"
    sections.append(f'<section id="{ids[path]}"><p class="src">{path}</p>{body}</section>')
for name, paths in GROUPS:
    items = "".join(f'<li><a href="#{ids[p]}">{html.escape(titles[p])}</a></li>' for p in paths)
    nav.append(f"<h2>{name}</h2><ul>{items}</ul>")

CSS = """
:root{--bg:#fff;--fg:#1c1f23;--muted:#667085;--line:#dfe3e8;--soft:#f5f6f8;--link:#1a56b0}
@media (prefers-color-scheme:dark){:root{--bg:#16181c;--fg:#e4e6ea;--muted:#98a2b3;--line:#333840;--soft:#22262c;--link:#7fb0f5}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.7 "Malgun Gothic",system-ui,sans-serif;display:grid;grid-template-columns:280px minmax(0,1fr)}
a{color:var(--link)}
nav{position:sticky;top:0;height:100vh;overflow:auto;padding:20px 16px;border-right:1px solid var(--line);font-size:14px;line-height:1.45}
nav h1{font-size:17px;margin:0 0 4px}
nav p{margin:0;color:var(--muted);font-size:12px}
nav h2{font-size:13px;color:var(--muted);margin:20px 0 6px}
nav ul{list-style:none;margin:0;padding:0}
nav li{margin:0 0 7px}
nav a{text-decoration:none}
nav a:hover,nav a:focus{text-decoration:underline}
main{max-width:1060px;padding:0 40px 80px}
section{padding-top:36px;border-bottom:1px solid var(--line);padding-bottom:36px;scroll-margin-top:0}
.src{margin:0;color:var(--muted);font:13px Consolas,monospace}
h1{font-size:28px;line-height:1.3;margin:6px 0 16px}
h2{font-size:21px;margin:32px 0 10px}
h3{font-size:17px;margin:24px 0 8px}
blockquote{margin:12px 0;padding:2px 14px;border-left:3px solid var(--line);color:var(--muted);font-size:15px}
.table{overflow-x:auto;margin:12px 0}
table{border-collapse:collapse;width:100%;font-size:14.5px;line-height:1.55}
th,td{border:1px solid var(--line);padding:7px 10px;text-align:left;vertical-align:top}
th{background:var(--soft);white-space:nowrap}
code{font:.92em Consolas,"Malgun Gothic",monospace;background:var(--soft);padding:1px 4px;border-radius:3px}
pre{background:var(--soft);padding:14px;overflow-x:auto;line-height:1.4;border-radius:4px}
pre code{background:none;padding:0;font-size:13px}
pre.mermaid{background:none;text-align:center}
@media (max-width:820px){body{display:block}nav{position:static;height:auto;border-right:0;border-bottom:1px solid var(--line);padding:16px}main{padding:0 16px 48px}}
"""
stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
page = f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>문서 검수</title><style>{CSS}</style></head>
<body><nav aria-label="문서 목록"><h1>문서 검수</h1><p>{stamp} 생성 · 원본은 .md</p>{"".join(nav)}</nav>
<main>{"".join(sections)}</main>
<script src="https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js"></script>
<script>window.mermaid&&mermaid.initialize({{startOnLoad:true,theme:matchMedia("(prefers-color-scheme: dark)").matches?"dark":"neutral"}})</script>
</body></html>
"""
os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, "w", encoding="utf-8", newline="\n").write(page)
print(f"OK {OUT}: {len(sources)} docs, {len(page.encode('utf-8'))} bytes")
