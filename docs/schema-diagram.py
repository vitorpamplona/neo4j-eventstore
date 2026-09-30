#!/usr/bin/env python3
# Regenerate: python3 docs/schema-diagram.py docs/schema.svg (then render the PNG with any browser).
# Keep it in step with docs/schema.md when a relation family changes.
"""Draws the neo4j-eventstore schema 2.0 as an SVG, in the style of a node/edge sketch."""
import math
import sys
from html import escape

W, H = 2460, 2210
COL = {
    "User": "#A61C9E",
    "Event": "#1E96DC",
    "Stub": "#9CCFEE",
    "Address": "#4FB33A",
    "Tag": "#F5B800",
}
out = []


def text(x, y, s, size=15, weight="normal", anchor="middle", fill="#111", rotate=None, halo=False, italic=False):
    tr = f' transform="rotate({rotate:.2f} {x:.1f} {y:.1f})"' if rotate is not None else ""
    h = ' stroke="#fff" stroke-width="5" paint-order="stroke" stroke-linejoin="round"' if halo else ""
    st = ' font-style="italic"' if italic else ""
    out.append(
        f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" font-weight="{weight}" text-anchor="{anchor}" '
        f'fill="{fill}"{h}{st}{tr}>{escape(s)}</text>'
    )


def node(x, y, r, kind, label=None, dashed=False, caption=None, caption_pos="below"):
    color = COL[kind]
    dash = ' stroke-dasharray="7 5"' if dashed else ""
    out.append(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{color}" stroke="#111" stroke-width="3.5"{dash}/>')
    label = label or kind
    fs = 14 if r >= 40 else 12
    w = max(len(label) * fs * 0.62 + 16, 40)
    out.append(
        f'<rect x="{x - w / 2:.1f}" y="{y - fs * 0.85:.1f}" width="{w:.1f}" height="{fs * 1.7:.1f}" rx="{fs * 0.85:.1f}" '
        f'fill="#fff" stroke="#111" stroke-width="1.5"/>'
    )
    text(x, y + fs * 0.36, label, size=fs)
    if caption:
        if caption_pos == "below":
            text(x, y + r + 18, caption, size=13, weight="bold")
        elif caption_pos == "above":
            text(x, y - r - 9, caption, size=13, weight="bold")
        elif caption_pos == "right":
            text(x + r + 8, y + 5, caption, size=13, weight="bold", anchor="start")


def props(x, y, lines, anchor="end", size=14, gap=19):
    for i, l in enumerate(lines):
        text(x, y + i * gap, l, size=size, anchor=anchor)


def edge(x1, y1, r1, x2, y2, r2, label, sub=(), size=15, t=0.5, bend=0.0):
    dx, dy = x2 - x1, y2 - y1
    d = math.hypot(dx, dy)
    ux, uy = dx / d, dy / d
    sx, sy = x1 + ux * (r1 + 2), y1 + uy * (r1 + 2)
    ex, ey = x2 - ux * (r2 + 5), y2 - uy * (r2 + 5)
    out.append(
        f'<line x1="{sx:.1f}" y1="{sy:.1f}" x2="{ex:.1f}" y2="{ey:.1f}" stroke="#111" stroke-width="3.2" marker-end="url(#arrow)"/>'
    )
    mx, my = sx + (ex - sx) * t, sy + (ey - sy) * t
    ang = math.degrees(math.atan2(dy, dx))
    if ang > 90 or ang < -90:
        ang += 180
    # label above the line, props below it (perpendicular offsets in the rotated frame)
    nx, ny = -math.sin(math.radians(ang)), math.cos(math.radians(ang))
    lx, ly = mx - nx * 8, my - ny * 8
    text(lx, ly, label, size=size, weight="bold", rotate=ang, halo=True)
    for i, s in enumerate(sub):
        off = 19 + i * 16
        text(mx + nx * off, my + ny * off, s, size=size - 3, rotate=ang, halo=True)


SHOWN = set()


def elbow(sx, sy, sr, tx, ty, tr, label, sub=(), bend=58, size=15):
    """Source fan → horizontal run into the target; the label sits on the horizontal run."""
    SHOWN.add(label)
    bx = sx + sr + bend
    a = math.atan2(ty - sy, bx - sx)
    x0, y0 = sx + sr * math.cos(a), sy + sr * math.sin(a)
    ex = tx - tr - 5
    out.append(
        f'<polyline points="{x0:.1f},{y0:.1f} {bx:.1f},{ty:.1f} {ex:.1f},{ty:.1f}" fill="none" stroke="#111" '
        f'stroke-width="3" stroke-linejoin="round" marker-end="url(#arrow)"/>'
    )
    mx = (bx + ex) / 2
    text(mx, ty - 8, label, size=size, weight="bold", halo=True)
    for i, t in enumerate(sub):
        text(mx, ty + 18 + i * 15, t, size=size - 3, halo=True, fill="#333")


# ---------------------------------------------------------------- page
out.append(
    '<defs><marker id="arrow" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
    '<path d="M0,0 L10,5 L0,10 z" fill="#111"/></marker></defs>'
)
out.append(f'<rect width="{W}" height="{H}" fill="#fff"/>')
text(40, 58, "neo4j-eventstore — graph schema 2.0", size=34, weight="bold", anchor="start")
text(
    40, 90,
    "Nodes: 4 labels. Relationship type = what the target IS to the event that states it (193 relations, docs/relations.md).",
    size=17, anchor="start", fill="#444",
)

# ---------------------------------------------------------------- core model
UX, UY = 330, 430  # User
EX, EY = 980, 430  # Event:Data
AX, AY = 980, 760  # own Address
R = 58

node(UX, UY, R, "User", caption=None)
props(UX - R - 14, UY - 4, ["pubkey:"], anchor="end")
text(UX - R - 14, UY + 22, "(names: on its kind 0, via 0:pubkey:)", size=12, anchor="end", fill="#555", italic=True)

node(EX, EY, R, "Event", label="Event:Data")
props(
    EX, EY - R - 169,
    ["id:", "kind:", "created_at:", "d:  (addressable kinds)", "expires_at:  (NIP-40)", "derived:  (derivation stamp)",
     "content / msats / title:  (curated, per kind)", "name / display_name / nip05:  (kind 0)"],
    anchor="middle", size=14, gap=19,
)

edge(EX, EY, R, UX, UY, R, "AUTHOR")

node(AX, AY, R, "Address")
props(AX + R + 16, AY - 30, ["id:  kind:pubkey:d", "kind:", "pubkey:", "d:  (hashed when > 1 KB)"], anchor="start")
text(AX + R + 16, AY + 60, "the NIP-01 slot: at most one held version,", size=13, anchor="start", fill="#444", italic=True)
text(AX + R + 16, AY + 77, "replaceable (kind:pubkey:) and addressable alike", size=13, anchor="start", fill="#444", italic=True)
edge(EX, EY, R, AX, AY, R, "ADDRESS", [], t=0.5)
edge(AX, AY, R, UX, UY, R, "AUTHOR", ["its pubkey"], t=0.45)

# targets of the event's statements
TX = 1620
targets = [
    (TX, 190, "Event", "Event:Data", False, "PARENT", ["via: e"]),
    (TX, 365, "Stub", "Event", True, "MENTION", ["via: content"]),
    (TX, 540, "User", "User", False, "REPORTED_USER", ["report: impersonation", "report_raw: …, via: p"]),
    (TX, 715, "Address", "Address", False, "QUOTE", ["via: q"]),
    (TX, 890, "Tag", "Tag", False, "HASHTAG", ["via: t"]),
]
for tx, ty, kind, label, dashed, rel, sub in targets:
    node(tx, ty, 50, kind, label=label, dashed=dashed)
    edge(EX, EY, R, tx, ty, 50, rel, sub, t=0.56)
text(TX + 66, 195, "a held event", size=13, anchor="start", fill="#444")
text(TX + 66, 360, "a stub: referenced,", size=13, anchor="start", fill="#444")
text(TX + 66, 377, "not held (id only)", size=13, anchor="start", fill="#444")
text(TX + 66, 545, "anyone referenced", size=13, anchor="start", fill="#444")
text(TX + 66, 720, "a coordinate, held or not", size=13, anchor="start", fill="#444")
props(TX + 66, 880, ["key:  type:value", "type:  hashtag, url, kind, geohash…", "value:"], anchor="start", size=14)

# legend / rules box
LX, LY = 1940, 150
out.append(f'<rect x="{LX}" y="{LY}" width="480" height="690" rx="14" fill="#F6F7F9" stroke="#999" stroke-width="1.5"/>')
text(LX + 20, LY + 38, "Rules", size=20, weight="bold", anchor="start")
rules = [
    "Every relationship starts at an",
    ":Event:Data — except an",
    "address's AUTHOR (its pubkey).",
    "",
    "The type is the relation name:",
    "PARENT, FOLLOW, REPORTED_USER…",
    "The source's kind is a node property,",
    "never part of the type.",
    "",
    "Edge properties = `via` (the tag",
    "name, or `content` for nostr: URIs)",
    "+ the relation's typed props",
    "(report, msats, rank, roles, labels…).",
    "",
    "One relation per meaning: counting",
    "a type is O(1) (degree store), so",
    "meanings queried apart are split:",
    "FOLLOW vs SUBSCRIBED,",
    "REPORTED_USER vs REPORTED_AUTHOR.",
    "",
    "Stubs keep references alive; a",
    "removed event becomes a stub.",
    "Private keys never become nodes.",
    "",
    "Every edge is its event's claim;",
    "only AUTHOR is signed fact.",
    "A value is keyed by what it is.",
]
for i, l in enumerate(rules):
    text(LX + 20, LY + 72 + i * 22, l, size=15, anchor="start")

# ---------------------------------------------------------------- relation families
PW, PH = 585, 560
PX0, PY0 = 25, 1000
text(40, PY0 - 18, "Relation families (a selection; every relation, with its targets and kinds, is in docs/vocabulary.md)",
     size=21, weight="bold", anchor="start")

T = {"U": ("User", "User"), "E": ("Event", "Event"), "A": ("Address", "Address"), "T": ("Tag", "Tag")}


def panel(col, row, title, sources):
    """sources: [(event_label, [(REL, [props], targetKey, targetCaption), …]), …]"""
    px, py = PX0 + col * (PW + 20), PY0 + row * (PH + 20)
    out.append(f'<rect x="{px}" y="{py}" width="{PW}" height="{PH}" rx="16" fill="#FBFBFC" stroke="#888" stroke-width="1.5"/>')
    text(px + 18, py + 34, title, size=19, weight="bold", anchor="start")
    rows = sum(len(e) for _, e in sources)
    top, bottom = py + 62, py + PH - 16
    # a one-edge source still needs room for its node and its kind caption
    weights = [max(len(e), 1.25) for _, e in sources]
    unit = (bottom - top) / sum(weights)
    y = top
    for (src_label, edges), wgt in zip(sources, weights):
        band = unit * wgt
        step = band / len(edges)
        ys = [y + 14 + (band - 14) / len(edges) * (k + 0.5) for k in range(len(edges))]
        sy = y + 14 + (band - 14) / 2
        sx = px + 64
        sr = 34
        node(sx, sy, sr, "Event", label="Event")
        for (rel, sub, tkey, cap), ty in zip(edges, ys):
            kind, label = T[tkey]
            tx = px + PW - 135
            r = min(27, step * 0.36)
            node(tx, ty, r, kind, label=label)
            if cap:
                # clear the label pill, which is wider than a small circle
                half = max(r, (len(label) * 12 * 0.62 + 16) / 2)
                text(tx + half + 6, ty + 5, cap, size=12, anchor="start", fill="#444")
            elbow(sx, sy, sr, tx, ty, r, rel, sub, size=14)
        text(sx, sy - sr - 8, src_label, size=13, weight="bold", halo=True)
        y += band


panel(0, 0, "Conversation — NIP-10 notes, NIP-22 comments", [
    ("kind 1 / 1111", [
        ("ROOT", ["also an :Address, or a url: / external: :Tag"], "E", "root"),
        ("PARENT", [], "E", "parent"),
        ("PARENT_AUTHOR", [], "U", ""),
        ("MENTION", ["via: p | e | content"], "U", ""),
        ("QUOTE", ["via: q"], "E", ""),
    ]),
])
panel(1, 0, "Reactions and reposts — NIP-25, NIP-18", [
    ("kind 7", [
        ("REACTED", ["the LAST e / a"], "E", ""),
        ("REACTED_AUTHOR", ["the LAST p"], "U", ""),
    ]),
    ("kind 6 / 16", [
        ("REPOSTED", [], "E", ""),
        ("REPOSTED_AUTHOR", [], "U", ""),
    ]),
])
panel(2, 0, "Zaps — NIP-57", [
    ("kind 9735 / 9734", [
        ("ZAPPED", ["msats"], "E", "or :Address"),
        ("ZAP_RECIPIENT", ["msats"], "U", ""),
        ("ZAP_SENDER", ["via: P | description"], "U", ""),
        ("ZAPPED_KIND", ["via: k"], "T", "kind:1"),
    ]),
])
panel(3, 0, "Social graph and lists — kind 3, NIP-51", [
    ("kind 3", [("FOLLOW", ["kind 3 only"], "U", "")]),
    ("kind 10000", [("MUTE", ["muted_kind"], "U", "or :Tag, :Event")]),
    ("NIP-51 lists", [
        ("SUBSCRIBED", [], "U", "or :Address"),
        ("BOOKMARK", [], "E", "or :Address"),
        ("MEMBER", ["roles, order, level"], "U", ""),
    ]),
])
panel(0, 1, "Moderation — NIP-56, NIP-32, NIP-09", [
    ("kind 1984", [
        ("REPORTED_USER", ["report, report_raw"], "U", "the person"),
        ("REPORTED", ["report, report_raw"], "E", "or :Address"),
        ("REPORTED_AUTHOR", ["report, report_raw"], "U", "its author"),
    ]),
    ("kind 1985", [("LABELED", ["labels: [ns:label]"], "E", "any target")]),
    ("kind 5", [("DELETED", [], "E", "stub once gone")]),
])
panel(1, 1, "Trust — NIP-85 assertions and 10040", [
    ("kind 30382", [
        ("SUBJECT", ["rank, followers, hops,", "zap/post/report counts…"], "U", "the d"),
    ]),
    ("kind 10040", [
        ("SERVICE_PROVIDER", ["via: 30382:rank"], "U", "the scorer"),
    ]),
    ("kind 30383 / 30384", [
        ("SUBJECT", ["the same metrics"], "E", "or :Address"),
    ]),
])
panel(2, 1, "Communities, groups, badges, events", [
    ("kind 4550", [
        ("COMMUNITY", [], "A", "34550"),
        ("APPROVED", [], "E", ""),
    ]),
    ("kind 34550", [("MODERATOR", [], "U", "")]),
    ("kind 8", [
        ("BADGE_DEFINITION", [], "A", "30009"),
        ("AWARDED", [], "U", ""),
    ]),
    ("kind 31925", [("CALENDAR_EVENT", ["status"], "A", "31922/3")]),
])
panel(3, 1, "Values, and tags any kind may carry", [
    ("any kind", [
        ("HASHTAG", ["via: t / i #…"], "T", "hashtag:nostr"),
        ("LOCATION", ["via: g"], "T", "geohash:u4pr"),
        ("REFERENCE", ["via: r"], "T", "url:https://…"),
        ("GROUP", ["via: h"], "T", "group:<id>"),
        ("CLIENT", ["via: client"], "A", "31990"),
        ("ZAP_SPLIT", ["weight"], "U", ""),
    ]),
])

# node legend strip (top)
ly = 128
x = 40
for kind, name in [("User", ":User"), ("Event", ":Event:Data"), ("Stub", ":Event (stub)"), ("Address", ":Address"), ("Tag", ":Tag")]:
    dash = ' stroke-dasharray="4 3"' if kind == "Stub" else ""
    out.append(f'<circle cx="{x + 10}" cy="{ly - 6}" r="10" fill="{COL[kind]}" stroke="#111" stroke-width="2"{dash}/>')
    text(x + 28, ly, name, size=16, anchor="start")
    x += 190
SHOWN.update({"AUTHOR", "ADDRESS", "PARENT", "MENTION", "REPORTED_USER", "QUOTE", "HASHTAG"})
TOTAL = 193
text(40, PY0 + 2 * (PH + 20) + 20,
     f"+ {TOTAL - len(SHOWN)} more relations, e.g. CITED, HIGHLIGHTED, ROOT_KIND, LANGUAGE, POLL, VOTED, WOT_ROOT, FORK … — every one with its targets in docs/relations.md",
     size=16, anchor="start", fill="#444")

svg = (
    f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
    f'font-family="Helvetica, Arial, sans-serif">' + "".join(out) + "</svg>"
)
open(sys.argv[1], "w").write(svg)
