"""markdown_utils — parse Telegram-style Markdown into ``TLRPC.MessageEntity``.

``parse_markdown(markdown)`` returns a ``ParsedMessage`` with:

- ``.text``     — plain text (markers stripped), UTF-16 offset space
- ``.entities`` — list of ``RawEntity``; each has ``.to_tlrpc_object()``

Supported inline markup (Telegram client flavour):

    **bold**  __italic__  ~~strikethrough~~  ||spoiler||  `code`
    ```code```  [text](url)  ![alt](tg://emoji?id=123)
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from java import jclass

TLRPC = jclass("org.telegram.tgnet.TLRPC")


class TLEntityType(Enum):
    CODE = "code"
    PRE = "pre"
    STRIKETHROUGH = "strikethrough"
    TEXT_LINK = "text_link"
    BOLD = "bold"
    ITALIC = "italic"
    UNDERLINE = "underline"
    SPOILER = "spoiler"
    CUSTOM_EMOJI = "custom_emoji"
    BLOCKQUOTE = "blockquote"


_TLRPC_ENTITY_MAP = {
    TLEntityType.BOLD: "TL_messageEntityBold",
    TLEntityType.ITALIC: "TL_messageEntityItalic",
    TLEntityType.UNDERLINE: "TL_messageEntityUnderline",
    TLEntityType.STRIKETHROUGH: "TL_messageEntityStrike",
    TLEntityType.SPOILER: "TL_messageEntitySpoiler",
    TLEntityType.CODE: "TL_messageEntityCode",
    TLEntityType.PRE: "TL_messageEntityPre",
    TLEntityType.TEXT_LINK: "TL_messageEntityTextUrl",
    TLEntityType.CUSTOM_EMOJI: "TL_messageEntityCustomEmoji",
    TLEntityType.BLOCKQUOTE: "TL_messageEntityBlockquote",
}


@dataclass
class RawEntity:
    type: TLEntityType
    offset: int = 0
    length: int = 0
    url: str = None
    language: str = None
    document_id: int = None
    collapsed: bool = None

    def to_tlrpc_object(self):
        cls_name = _TLRPC_ENTITY_MAP.get(self.type)
        if cls_name is None:
            return None
        obj = getattr(TLRPC, cls_name)()
        obj.offset = self.offset
        obj.length = self.length
        if self.type == TLEntityType.TEXT_LINK and self.url is not None:
            obj.url = self.url
        elif self.type == TLEntityType.PRE and self.language:
            obj.language = self.language
        elif self.type == TLEntityType.CUSTOM_EMOJI and self.document_id is not None:
            obj.document_id = self.document_id
        elif self.type == TLEntityType.BLOCKQUOTE and self.collapsed is not None:
            obj.collapsed = self.collapsed
        return obj


@dataclass
class ParsedMessage:
    text: str = ""
    entities: list = field(default_factory=list)


def to_utf16_len(s):
    """Length of *s* in UTF-16 code units (Telegram entity offsets use this)."""
    if not s:
        return 0
    return len(s.encode("utf-16-le")) // 2


def count_chars_until(string, stop_chars, start_index):
    """Number of UTF-16 code units from *start_index* until a stop char."""
    if string is None:
        return 0
    idx = start_index
    while idx < len(string):
        if string[idx] in stop_chars:
            break
        idx += 1
    return to_utf16_len(string[start_index:idx])


def _match_token(s, i):
    """Return (type, content_start, content_end, extra) for markup at index i, or None."""
    n = len(s)

    if s.startswith("**", i):
        close = s.find("**", i + 2)
        if close != -1 and close != i + 2:
            return (TLEntityType.BOLD, i + 2, close, None)

    if s.startswith("__", i):
        close = s.find("__", i + 2)
        if close != -1 and close != i + 2:
            return (TLEntityType.UNDERLINE, i + 2, close, None)

    if s.startswith("~~", i):
        close = s.find("~~", i + 2)
        if close != -1 and close != i + 2:
            return (TLEntityType.STRIKETHROUGH, i + 2, close, None)

    if s.startswith("||", i):
        close = s.find("||", i + 2)
        if close != -1 and close != i + 2:
            return (TLEntityType.SPOILER, i + 2, close, None)

    if s.startswith("![", i):
        link = _match_link(s, i, len("!["))
        if link is not None:
            inner_start, inner_end, url = link
            emoji_id = None
            if url and url.startswith("tg://emoji?id="):
                try:
                    emoji_id = int(url[len("tg://emoji?id="):])
                except Exception:
                    emoji_id = None
            if emoji_id is not None:
                return (TLEntityType.CUSTOM_EMOJI, inner_start, inner_end, emoji_id)

    if s.startswith("[", i):
        link = _match_link(s, i, 1)
        if link is not None:
            inner_start, inner_end, url = link
            return (TLEntityType.TEXT_LINK, inner_start, inner_end, url)

    if s.startswith("`", i):
        close = s.find("`", i + 1)
        if close != -1 and close != i + 1:
            return (TLEntityType.CODE, i + 1, close, None)

    # Single-char markers: *bold* / _italic_ / ~strikethrough~
    # A marker is only treated as markup if it is not followed/preceded by the same
    # char (i.e. not part of **, __, ~~ which were handled above) and has a closing pair.
    for marker, etype in (("*", TLEntityType.BOLD),
                          ("_", TLEntityType.ITALIC),
                          ("~", TLEntityType.STRIKETHROUGH)):
        if s.startswith(marker, i):
            if i + 1 < n and s[i + 1] == marker:
                continue  # part of a doubled marker already handled (or plain text)
            close = s.find(marker, i + 1)
            if close == -1 or close == i + 1:
                continue
            return (etype, i + 1, close, None)

    return None


def _match_link(s, i, prefix_len):
    """For ``[text](url)`` or ``![alt](url)`` starting at i (after ``[``/``![``)."""
    close_bracket = s.find("]", i + prefix_len)
    if close_bracket == -1:
        return None
    if close_bracket + 1 >= len(s) or s[close_bracket + 1] != "(":
        return None
    close_paren = s.find(")", close_bracket + 2)
    if close_paren == -1:
        return None
    inner_start = i + prefix_len
    inner_end = close_bracket
    url = s[close_bracket + 2:close_paren]
    return (inner_start, inner_end, url)


def parse_markdown(markdown):
    """Parse *markdown* into a ``ParsedMessage`` (UTF-16 offsets)."""
    if not markdown:
        return ParsedMessage("", [])

    # Line-level blockquote handling: "> text" and "**> text" (expandable).
    # The body is parsed inline by the token scanner below; here we only strip
    # the prefix and record the BLOCKQUOTE entity range for each quote line.
    quote_ranges = []  # (start_utf16, end_utf16, collapsed)
    has_quotes = False
    for raw in markdown.splitlines(True):
        stripped = raw.lstrip("\ufeff")
        if stripped.startswith("**> ") or stripped.startswith("**>"):
            has_quotes = True
        elif stripped.startswith("> "):
            has_quotes = True
    if has_quotes:
        out_lines = []
        running_offset = 0
        for raw in markdown.splitlines(True):
            stripped = raw.lstrip("\ufeff")
            collapsed = False
            if stripped.startswith("**> "):
                collapsed = True
                stripped = stripped[4:]
            elif stripped.startswith("**>"):
                collapsed = True
                stripped = stripped[3:]
            elif stripped.startswith("> "):
                stripped = stripped[2:]
            elif stripped.startswith(">"):
                stripped = stripped[1:]
            if collapsed or raw.lstrip("\ufeff").startswith((">", "**>")):
                start = to_utf16_len("".join(out_lines))
                out_lines.append(stripped)
                end = to_utf16_len("".join(out_lines))
                if end > start:
                    quote_ranges.append((start, end, collapsed))
            else:
                out_lines.append(raw)
        markdown = "".join(out_lines)

    text_parts = []
    entities = []
    i = 0
    n = len(markdown)
    plain = []

    def flush_plain():
        if plain:
            text_parts.append("".join(plain))
            plain.clear()

    while i < n:
        # fenced code block
        if markdown.startswith("```", i):
            close = markdown.find("```", i + 3)
            if close != -1:
                flush_plain()
                content = markdown[i + 3:close]
                language = None
                nl = content.find("\n")
                if nl != -1:
                    first = content[:nl].strip()
                    if first:
                        language = first
                    content = content[nl + 1:]
                content = content.rstrip("\n")
                offset = to_utf16_len("".join(text_parts))
                length = to_utf16_len(content)
                text_parts.append(content)
                entities.append(RawEntity(TLEntityType.PRE, offset, length, language=language))
                i = close + 3
                continue

        token = _match_token(markdown, i)
        if token is not None:
            kind, content_start, content_end, extra = token
            flush_plain()
            content = markdown[content_start:content_end]
            offset = to_utf16_len("".join(text_parts))
            length = to_utf16_len(content)
            text_parts.append(content)
            _emit_entity(kind, extra, offset, length, entities)
            # closing marker length
            if kind == TLEntityType.TEXT_LINK or kind == TLEntityType.CUSTOM_EMOJI:
                close_paren = markdown.find(")", content_end)
                i = (close_paren + 1) if close_paren != -1 else content_end + 2
            else:
                opener = markdown[i]
                doubled = (opener in ("*", "_", "~")) and (i + 1 < n and markdown[i + 1] == opener)
                if opener in ("*", "_", "~") and not doubled:
                    i = content_end + 1  # single-char closing marker
                else:
                    i = content_end + _closing_len(kind)
            continue

        plain.append(markdown[i])
        i += 1

    flush_plain()
    for start, end, collapsed in quote_ranges:
        entities.append(RawEntity(TLEntityType.BLOCKQUOTE, start, end - start, collapsed=collapsed))
    entities.sort(key=lambda e: (e.offset, e.length))
    return ParsedMessage("".join(text_parts), entities)


def _closing_len(kind):
    return len(_CLOSING.get(kind, ""))


_CLOSING = {
    TLEntityType.BOLD: "**",
    TLEntityType.ITALIC: "__",
    TLEntityType.UNDERLINE: "__",
    TLEntityType.STRIKETHROUGH: "~~",
    TLEntityType.SPOILER: "||",
    TLEntityType.CODE: "`",
    TLEntityType.PRE: "```",
}


def _emit_entity(kind, extra, offset, length, entities):
    if kind == TLEntityType.TEXT_LINK:
        entities.append(RawEntity(kind, offset, length, url=extra))
    elif kind == TLEntityType.CUSTOM_EMOJI:
        entities.append(RawEntity(kind, offset, length, document_id=extra))
    elif kind == TLEntityType.UNDERLINE:
        entities.append(RawEntity(kind, offset, length))
    else:
        entities.append(RawEntity(kind, offset, length))
