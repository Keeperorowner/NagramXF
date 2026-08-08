"""text_formatting — parse HTML / Markdown into plain text + ``TLRPC.MessageEntity``.

``parse_text(text, parse_mode='HTML', is_caption=False)`` returns a dict::

    {"message": plain_text, "entities": [TLRPC.MessageEntity, ...]}

or ``{"caption": ...}`` when ``is_caption`` is True.
"""
from __future__ import annotations

import re

from java import jclass

from markdown_utils import parse_markdown, TLEntityType, RawEntity, to_utf16_len

TLRPC = jclass("org.telegram.tgnet.TLRPC")

# HTML tag -> entity factory name
_HTML_TAGS = {
    "b": "TL_messageEntityBold",
    "strong": "TL_messageEntityBold",
    "i": "TL_messageEntityItalic",
    "em": "TL_messageEntityItalic",
    "u": "TL_messageEntityUnderline",
    "s": "TL_messageEntityStrike",
    "del": "TL_messageEntityStrike",
    "strike": "TL_messageEntityStrike",
    "code": "TL_messageEntityCode",
    "pre": "TL_messageEntityPre",
    "spoiler": "TL_messageEntitySpoiler",
    "tg-spoiler": "TL_messageEntitySpoiler",
    "blockquote": "TL_messageEntityBlockquote",
    "a": "TL_messageEntityTextUrl",
    "emoji": "TL_messageEntityCustomEmoji",
}

_TAG_RE = re.compile(r"</?(b|strong|i|em|u|s|del|strike|code|pre|spoiler|tg-spoiler|blockquote|a|emoji)(?:\s+[^>]*)?>", re.IGNORECASE)
_ATTR_RE = {
    "href": re.compile(r'href\s*=\s*"([^"]*)"', re.IGNORECASE),
    "language": re.compile(r'language\s*=\s*"([^"]*)"', re.IGNORECASE),
    "id": re.compile(r'id\s*=\s*"([^"]*)"', re.IGNORECASE),
    "expandable": re.compile(r'\bexpandable\b', re.IGNORECASE),
    "collapsed": re.compile(r'\bcollapsed\b', re.IGNORECASE),
}


def parse_text(text, parse_mode="HTML", is_caption=False):
    key = "caption" if is_caption else "message"
    if text is None:
        text = ""
    mode = str(parse_mode).lower() if parse_mode else "html"
    if mode == "markdown":
        parsed = parse_markdown(text)
        entities = [e.to_tlrpc_object() for e in parsed.entities]
        return {key: parsed.text, "entities": [e for e in entities if e is not None]}
    if mode == "html":
        return _parse_html(text, key)
    raise ValueError("Unsupported parse_mode: %r (expected 'html' or 'markdown')" % (parse_mode,))


def _parse_html(text, key):
    text_parts = []
    entities = []
    # stack of open entity descriptors: (tag_name, entity_obj)
    stack = []
    pos = 0

    def offset():
        return to_utf16_len("".join(text_parts))

    for m in _TAG_RE.finditer(text):
        # emit text before this tag
        text_parts.append(text[pos:m.start()])
        token = m.group(0)
        tag = m.group(1).lower()
        closing = token.startswith("</")

        if closing:
            # pop matching open entity
            for idx in range(len(stack) - 1, -1, -1):
                name, obj = stack[idx]
                if name == tag:
                    obj.length = offset() - obj.offset
                    entities.append(obj)
                    del stack[idx]
                    break
        else:
            factory = _HTML_TAGS.get(tag)
            if factory is not None:
                obj = getattr(TLRPC, factory)()
                obj.offset = offset()
                if tag == "a":
                    mh = _ATTR_RE["href"].search(token)
                    obj.url = mh.group(1) if mh else ""
                elif tag == "pre":
                    ml = _ATTR_RE["language"].search(token)
                    if ml:
                        obj.language = ml.group(1)
                elif tag == "blockquote":
                    me = _ATTR_RE["expandable"].search(token)
                    mc = _ATTR_RE["collapsed"].search(token)
                    obj.collapsed = bool(me or mc)
                elif tag == "emoji":
                    mi = _ATTR_RE["id"].search(token)
                    if mi:
                        try:
                            obj.document_id = int(mi.group(1))
                        except Exception:
                            pass
                stack.append((tag, obj))
        pos = m.end()

    text_parts.append(text[pos:])
    # close any unclosed entities at the end
    for name, obj in reversed(stack):
        obj.length = offset() - obj.offset
        entities.append(obj)

    return {key: "".join(text_parts), "entities": entities}
