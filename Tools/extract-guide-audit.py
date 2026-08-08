from __future__ import annotations

import html
import re
from pathlib import Path

from bs4 import BeautifulSoup


ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / "docs" / "plugin-api-reference-current-snapshot.md"
GUIDE = ROOT / "docs" / "plugin-api-reference-guide.md"
REPORT = ROOT / "docs" / "plugin-api-reference-guide-audit.md"


def extract_pages(text: str) -> list[tuple[str, str]]:
    pattern = re.compile(
        r"^### (?P<number>\d+)\. `(?P<path>/docs[^`]+)`\s*$.*?^`{4}html\s*$\n(?P<html>.*?)^`{5}\s*$",
        re.MULTILINE | re.DOTALL,
    )
    return [(m.group("path"), m.group("html")) for m in pattern.finditer(text)]


def render_text(source: str) -> list[str]:
    soup = BeautifulSoup(source, "html.parser")
    main = soup.find("main")
    if main is None:
        return []

    # Remove site chrome and navigation that is not page content.
    for tag in main.find_all(["script", "style", "noscript", "nav", "header", "footer"]):
        tag.decompose()

    lines: list[str] = []
    for node in main.find_all(["h1", "h2", "h3", "h4", "p", "li", "pre", "table"]):
        if node.name == "pre":
            value = node.get_text("\n", strip=False).strip("\n")
            if value:
                lines.append("CODE:" + value)
        elif node.name == "table":
            value = " ".join(node.stripped_strings)
            if value:
                lines.append("TABLE:" + value)
        else:
            value = " ".join(node.stripped_strings)
            if value:
                lines.append(f"{node.name.upper()}:{value}")
    return lines


def main() -> None:
    snapshot_text = SNAPSHOT.read_text(encoding="utf-8")
    guide_text = GUIDE.read_text(encoding="utf-8")
    pages = extract_pages(snapshot_text)
    guide_lower = guide_text.lower()

    report: list[str] = [
        "# plugin-api-reference-guide 对照审计",
        "",
        "依据：`docs/plugin-api-reference-current-snapshot.md` 中的官网原始 HTML 快照。",
        "",
        f"官网快照页面数：{len(pages)}",
        "",
        "## 页面正文提取摘要",
        "",
    ]

    for path, source in pages:
        rendered = render_text(source)
        title = next((x[5:] for x in rendered if x.startswith("H1:")), "")
        api_like = [
            x[5:] for x in rendered
            if x.startswith(("H2:", "H3:", "H4:"))
            and any(token in x.lower() for token in ("api", "method", "function", "class", "settings", "hook", "callback", "asset", "metadata"))
        ]
        missing = []
        for item in api_like:
            normalized = re.sub(r"[^a-z0-9]+", " ", item.lower()).strip()
            if normalized and normalized not in re.sub(r"[^a-z0-9]+", " ", guide_lower):
                missing.append(item)
        report.append(f"### `{path}`")
        report.append("")
        report.append(f"- 官网标题：{title}")
        report.append(f"- 正文节点：{len(rendered)}")
        report.append(f"- 代码块：{sum(x.startswith('CODE:') for x in rendered)}")
        report.append(f"- 表格：{sum(x.startswith('TABLE:') for x in rendered)}")
        if missing:
            report.append("- 可能需要人工复核的章节：")
            report.extend(f"  - {item}" for item in missing)
        else:
            report.append("- 章节关键词：未发现明显缺失")
        report.append("")

    REPORT.write_text("\n".join(report) + "\n", encoding="utf-8")
    print(f"pages={len(pages)} report={REPORT}")


if __name__ == "__main__":
    main()
