from __future__ import annotations

import html
import re
from pathlib import Path

from bs4 import BeautifulSoup, NavigableString, Tag


ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / "docs" / "plugin-api-reference-current-snapshot.md"
GUIDE = ROOT / "docs" / "plugin-api-reference-guide.md"
REPORT = ROOT / "docs" / "plugin-api-reference-guide-audit.md"


def extract_pages(text: str) -> list[tuple[str, str]]:
    lines = text.splitlines()
    pages: list[tuple[str, str]] = []
    index = 0
    while index < len(lines):
        match = re.match(r"^### \d+\. `(?P<path>/docs[^`]*)`$", lines[index])
        if not match:
            index += 1
            continue
        path = match.group("path")
        while index < len(lines) and lines[index] != "````html":
            index += 1
        if index >= len(lines):
            raise ValueError(f"opening HTML fence not found for {path}")
        index += 1
        start = index
        while index < len(lines) and lines[index] != "````":
            index += 1
        if index >= len(lines):
            raise ValueError(f"closing HTML fence not found for {path}")
        pages.append((path, "\n".join(lines[start:index])))
        index += 1
    return pages


def inline(node: object) -> str:
    if isinstance(node, NavigableString):
        return str(node).replace("\n", " ")
    if not isinstance(node, Tag):
        return ""
    name = node.name.lower()
    if name in {"script", "style", "svg", "button"}:
        return ""
    if name == "br":
        return "\n"
    if name == "code":
        return f"`{node.get_text('', strip=False)}`"
    if name in {"strong", "b"}:
        return f"**{''.join(inline(child) for child in node.children)}**"
    if name in {"em", "i"}:
        return f"*{''.join(inline(child) for child in node.children)}*"
    if name in {"del", "s"}:
        return f"~~{''.join(inline(child) for child in node.children)}~~"
    if name == "a":
        label = "".join(inline(child) for child in node.children).strip()
        href = node.get("href", "")
        return f"[{label}]({href})" if href else label
    return "".join(inline(child) for child in node.children)


def table_markdown(table: Tag) -> list[str]:
    rows: list[list[str]] = []
    for row in table.find_all("tr"):
        cells = row.find_all(["th", "td"], recursive=False)
        if cells:
            rows.append([" ".join(inline(cell).split()) for cell in cells])
    if not rows:
        return []
    width = max(len(row) for row in rows)
    rows = [row + [""] * (width - len(row)) for row in rows]
    output = ["| " + " | ".join(rows[0]) + " |", "|" + "---|" * width]
    output.extend("| " + " | ".join(row) + " |" for row in rows[1:])
    return output


def block(node: object, level: int = 0) -> list[str]:
    if not isinstance(node, Tag):
        return []
    name = node.name.lower()
    if name in {"script", "style", "svg", "button", "nav", "header", "footer"}:
        return []
    if name in {"h1", "h2", "h3", "h4", "h5", "h6"}:
        return [f"{'#' * int(name[1:])} {inline(node).strip()}", ""]
    if name == "pre":
        code = node.get_text("", strip=False).strip("\n")
        code_tag = node.find("code")
        language = ""
        if code_tag:
            classes = code_tag.get("class", [])
            for cls in classes:
                if cls.startswith("language-"):
                    language = cls[len("language-"):]
                    break
        return [f"```{language}", code, "```", ""]
    if name == "table":
        return table_markdown(node) + [""]
    if name == "blockquote":
        value = " ".join(inline(node).split())
        return [f"> {value}", ""] if value else []
    if name in {"ul", "ol"}:
        output: list[str] = []
        number = 1
        for item in node.find_all("li", recursive=False):
            value = " ".join(inline(item).split())
            marker = f"{number}." if name == "ol" else "-"
            output.append(f"{marker} {value}")
            number += 1
        return output + [""]
    if name == "hr":
        return ["---", ""]
    if name == "p":
        value = inline(node).strip()
        return [value, ""] if value else []
    output: list[str] = []
    for child in node.children:
        if isinstance(child, Tag):
            output.extend(block(child, level + 1))
    return output


def page_markdown(path: str, source: str) -> tuple[str, dict[str, int | str]]:
    soup = BeautifulSoup(source, "html.parser")
    article = soup.find("article")
    if article is None:
        raise ValueError(f"article not found for {path}")
    for tag in article.find_all(["script", "style", "svg", "button", "nav", "header", "footer"]):
        tag.decompose()

    content = article.find(class_=lambda value: value and "prose" in value.split())
    if content is None:
        content = article
    title = article.find("h1")
    description = title.find_next_sibling("p") if title else None
    output: list[str] = [f"# {title.get_text(' ', strip=True) if title else path}", "", f"> Source: https://plugins.exteragram.app{path}", ""]
    if description:
        output.extend([description.get_text(" ", strip=True), ""])
    for child in content.children:
        if isinstance(child, Tag):
            output.extend(block(child))
    while output and not output[-1].strip():
        output.pop()
    return "\n".join(output) + "\n", {
        "title": title.get_text(" ", strip=True) if title else path,
        "headings": len(content.find_all(["h2", "h3", "h4", "h5", "h6"])),
        "code": len(content.find_all("pre")),
        "tables": len(content.find_all("table")),
    }


def main() -> None:
    pages = extract_pages(SNAPSHOT.read_text(encoding="utf-8"))
    sections: list[str] = [
        "# exteraGram Plugin API Website Content Guide",
        "",
        "> This file contains the readable website content extracted from the current raw HTML snapshot. It keeps the rendered documentation text, headings, lists, tables, code examples, and source URLs; it excludes website HTML, JavaScript, CSS, navigation chrome, search UI, and footer controls.",
        "",
        f"> Source snapshot: `plugin-api-reference-current-snapshot.md` ({len(pages)} pages)",
        "",
        "## Contents",
        "",
    ]
    for number, (path, _) in enumerate(pages, 1):
        sections.append(f"{number}. [`{path}`](#{path.removeprefix('/').replace('/', '-')})")
    sections.append("")
    sections.append("## Pages")
    sections.append("")

    report: list[str] = [
        "# Guide Audit",
        "",
        "Generated from `plugin-api-reference-current-snapshot.md`.",
        "",
        "| # | Path | Title | Headings | Code blocks | Tables |",
        "|---:|---|---|---:|---:|---:|",
    ]
    for number, (path, source) in enumerate(pages, 1):
        rendered, stats = page_markdown(path, source)
        sections.append(f"<a id=\"{path.removeprefix('/').replace('/', '-') }\"></a>")
        sections.append("")
        sections.append(rendered)
        report.append(f"| {number} | `{path}` | {stats['title']} | {stats['headings']} | {stats['code']} | {stats['tables']} |")

    GUIDE.write_text("\n".join(sections), encoding="utf-8")
    REPORT.write_text("\n".join(report) + "\n", encoding="utf-8")
    print(f"pages={len(pages)} guide={GUIDE} report={REPORT}")


if __name__ == "__main__":
    main()
