"""Renders the ThermalBench developer manual to PDF.

The manual's text lives in `manual_content.py` so prose and layout stay
separate. Run from the repository root:

    python DOCS/build_manual.py
"""

from __future__ import annotations

import os
import sys
from datetime import date

from reportlab.lib import colors
from reportlab.lib.enums import TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    BaseDocTemplate,
    CondPageBreak,
    Frame,
    Image,
    KeepTogether,
    ListFlowable,
    ListItem,
    NextPageTemplate,
    PageBreak,
    PageTemplate,
    Paragraph,
    Preformatted,
    Spacer,
    Table,
    TableStyle,
)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Which content module to render. Layout lives here; prose lives there, so a
# second document for a different audience needs no changes to this file.
CONTENT_MODULE = os.environ.get("THERMALBENCH_CONTENT", "manual_content")
_content = __import__(CONTENT_MODULE)
DOCUMENT = _content.DOCUMENT
SUBTITLE = _content.SUBTITLE
TITLE = _content.TITLE
VERSION = _content.VERSION
OUTPUT_NAME = getattr(_content, "OUTPUT_NAME", "ThermalBench-Developer-Manual.pdf")
RUNNING_HEAD = getattr(_content, "RUNNING_HEAD", "ThermalBench — Developer Manual")

INK = colors.HexColor("#12181F")
MUTED = colors.HexColor("#5A6672")
ACCENT = colors.HexColor(getattr(_content, "ACCENT_HEX", "#00629E"))
WARM = colors.HexColor("#C2410C")
RULE = colors.HexColor("#D6DDE4")
CODE_BG = colors.HexColor("#F4F6F8")
TABLE_HEAD = colors.HexColor("#EAF1F7")

PAGE_W, PAGE_H = A4
MARGIN_L = 24 * mm
MARGIN_R = 20 * mm
MARGIN_T = 22 * mm
MARGIN_B = 20 * mm


def build_styles():
    base = getSampleStyleSheet()
    s = {}
    s["title"] = ParagraphStyle(
        "title", parent=base["Title"], fontName="Helvetica-Bold",
        fontSize=30, leading=34, textColor=INK, alignment=TA_LEFT, spaceAfter=6,
    )
    s["subtitle"] = ParagraphStyle(
        "subtitle", parent=base["Normal"], fontName="Helvetica",
        fontSize=13, leading=18, textColor=MUTED, spaceAfter=4,
    )
    # keepWithNext stops a heading being left stranded at the foot of a page.
    s["h1"] = ParagraphStyle(
        "h1", parent=base["Heading1"], fontName="Helvetica-Bold",
        fontSize=17, leading=21, textColor=INK, spaceBefore=16, spaceAfter=7,
        keepWithNext=1,
    )
    s["h2"] = ParagraphStyle(
        "h2", parent=base["Heading2"], fontName="Helvetica-Bold",
        fontSize=12.5, leading=16, textColor=ACCENT, spaceBefore=12, spaceAfter=4,
        keepWithNext=1,
    )
    s["h3"] = ParagraphStyle(
        "h3", parent=s["h2"], fontSize=10.5, leading=14, textColor=INK,
        spaceBefore=8, spaceAfter=3,
    )
    s["body"] = ParagraphStyle(
        "body", parent=base["BodyText"], fontName="Helvetica",
        fontSize=9.8, leading=14.6, textColor=INK, alignment=TA_JUSTIFY, spaceAfter=7,
    )
    s["lead"] = ParagraphStyle(
        "lead", parent=s["body"], fontSize=11, leading=16.5, textColor=INK, spaceAfter=9,
    )
    s["bullet"] = ParagraphStyle(
        "bullet", parent=s["body"], alignment=TA_LEFT, spaceAfter=3.5,
    )
    s["code"] = ParagraphStyle(
        "code", parent=base["Code"], fontName="Courier",
        fontSize=8.1, leading=11.4, textColor=INK,
        leftIndent=7, rightIndent=7, spaceBefore=3, spaceAfter=3,
    )
    s["callout"] = ParagraphStyle(
        "callout", parent=s["body"], fontSize=9.6, leading=14,
        leftIndent=9, rightIndent=9, spaceBefore=4, spaceAfter=4, alignment=TA_LEFT,
    )
    s["cell"] = ParagraphStyle(
        "cell", parent=base["Normal"], fontName="Helvetica",
        fontSize=8.4, leading=11.6, textColor=INK,
    )
    s["cellhead"] = ParagraphStyle(
        "cellhead", parent=s["cell"], fontName="Helvetica-Bold", textColor=INK,
    )
    s["cellcode"] = ParagraphStyle(
        "cellcode", parent=s["cell"], fontName="Courier", fontSize=8.0,
    )
    s["caption"] = ParagraphStyle(
        "caption", parent=base["Normal"], fontName="Helvetica-Oblique",
        fontSize=8.4, leading=11.5, textColor=MUTED, spaceAfter=9,
    )
    s["tocentry"] = ParagraphStyle(
        "tocentry", parent=base["Normal"], fontName="Helvetica",
        fontSize=10, leading=17, textColor=INK,
    )
    s["tocnum"] = ParagraphStyle(
        "tocnum", parent=base["Normal"], fontName="Helvetica-Bold",
        fontSize=10, leading=17, textColor=ACCENT,
    )
    return s


ST = build_styles()


def code_block(text: str):
    """Monospaced block on a tinted panel, kept on one page where it fits."""
    lines = text.strip("\n").rstrip()
    inner = Preformatted(lines, ST["code"])
    t = Table([[inner]], colWidths=[PAGE_W - MARGIN_L - MARGIN_R])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), CODE_BG),
        ("BOX", (0, 0), (-1, -1), 0.5, RULE),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))
    return t


def callout(text: str, tone: str = "accent"):
    edge = WARM if tone == "warn" else ACCENT
    p = Paragraph(text, ST["callout"])
    t = Table([[p]], colWidths=[PAGE_W - MARGIN_L - MARGIN_R])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#FBFCFD")),
        ("LINEBEFORE", (0, 0), (0, -1), 2.4, edge),
        ("LEFTPADDING", (0, 0), (-1, -1), 10),
        ("RIGHTPADDING", (0, 0), (-1, -1), 9),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))
    return t


def make_table(rows, widths=None, code_cols=()):
    header, *body = rows
    n = len(header)
    total = PAGE_W - MARGIN_L - MARGIN_R
    if widths is None:
        widths = [total / n] * n
    else:
        scale = total / sum(widths)
        widths = [w * scale for w in widths]

    data = [[Paragraph(c, ST["cellhead"]) for c in header]]
    for row in body:
        data.append([
            Paragraph(c, ST["cellcode"] if i in code_cols else ST["cell"])
            for i, c in enumerate(row)
        ])

    t = Table(data, colWidths=widths, repeatRows=1, hAlign="LEFT")
    style = [
        ("BACKGROUND", (0, 0), (-1, 0), TABLE_HEAD),
        ("LINEBELOW", (0, 0), (-1, 0), 0.7, ACCENT),
        ("LINEBELOW", (0, 1), (-1, -2), 0.3, RULE),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 7),
        ("RIGHTPADDING", (0, 0), (-1, -1), 7),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("BOX", (0, 0), (-1, -1), 0.5, RULE),
    ]
    for i in range(2, len(data), 2):
        style.append(("BACKGROUND", (0, i), (-1, i), colors.HexColor("#FAFBFC")))
    t.setStyle(TableStyle(style))
    return t


def bullets(items, numbered=False):
    flow = [ListItem(Paragraph(i, ST["bullet"]), leftIndent=14) for i in items]
    return ListFlowable(
        flow,
        bulletType="1" if numbered else "bullet",
        bulletFontName="Helvetica",
        bulletFontSize=8.5,
        start="1" if numbered else None,
        leftIndent=15,
        bulletOffsetY=-0.5,
        spaceAfter=7,
    )


class ManualDoc(BaseDocTemplate):
    """
    Two page templates: a bare cover and the running body.

    The running header names the section the page belongs to. That cannot be
    known while the page is being drawn, because the header is painted before
    the flowables land on it, so the document is built twice: the first pass
    records which section starts on which page, the second pass draws using
    that map. Without it every page is headed with the previous section.
    """

    def __init__(self, path, section_map=None, **kw):
        super().__init__(path, pagesize=A4,
                         leftMargin=MARGIN_L, rightMargin=MARGIN_R,
                         topMargin=MARGIN_T, bottomMargin=MARGIN_B, **kw)
        frame = Frame(MARGIN_L, MARGIN_B,
                      PAGE_W - MARGIN_L - MARGIN_R,
                      PAGE_H - MARGIN_T - MARGIN_B, id="body")
        self.addPageTemplates([
            PageTemplate(id="cover", frames=[frame], onPage=self.cover_page),
            PageTemplate(id="body", frames=[frame], onPage=self.body_page),
        ])
        self.section_map = section_map or {}
        self.section_starts = []

    def afterFlowable(self, flowable):
        if isinstance(flowable, Paragraph) and flowable.style.name == "h1":
            self.section_starts.append((self.page, flowable.getPlainText()))

    def resolved_sections(self, last_page):
        """page number -> the section heading in effect on that page."""
        # A page where several sections start is headed with the first of them;
        # the pages after it belong to the last.
        resolved, current = {}, ""
        first, last = {}, {}
        for page, title in self.section_starts:
            first.setdefault(page, title)
            last[page] = title
        for page in range(1, last_page + 1):
            resolved[page] = first.get(page, current)
            current = last.get(page, current)
        return resolved

    @property
    def section(self):
        return self.section_map.get(self.page, "")

    def cover_page(self, canvas, doc):
        canvas.saveState()
        canvas.setFillColor(ACCENT)
        canvas.rect(0, PAGE_H - 13 * mm, PAGE_W, 13 * mm, stroke=0, fill=1)
        canvas.setFillColor(WARM)
        canvas.rect(0, PAGE_H - 15.4 * mm, PAGE_W, 2.4 * mm, stroke=0, fill=1)
        canvas.restoreState()

    def body_page(self, canvas, doc):
        canvas.saveState()
        canvas.setFont("Helvetica", 7.6)
        canvas.setFillColor(MUTED)
        canvas.drawString(MARGIN_L, PAGE_H - 13 * mm, RUNNING_HEAD)
        if self.section:
            canvas.drawRightString(PAGE_W - MARGIN_R, PAGE_H - 13 * mm, self.section)
        canvas.setStrokeColor(RULE)
        canvas.setLineWidth(0.5)
        canvas.line(MARGIN_L, PAGE_H - 14.6 * mm, PAGE_W - MARGIN_R, PAGE_H - 14.6 * mm)
        canvas.line(MARGIN_L, MARGIN_B - 5 * mm, PAGE_W - MARGIN_R, MARGIN_B - 5 * mm)
        canvas.drawString(MARGIN_L, MARGIN_B - 9.5 * mm, f"v{VERSION}")
        canvas.drawRightString(PAGE_W - MARGIN_R, MARGIN_B - 9.5 * mm, str(canvas.getPageNumber()))
        canvas.restoreState()


def cover(story):
    story.append(Spacer(1, 46 * mm))
    story.append(Paragraph(TITLE, ST["title"]))
    story.append(Paragraph(SUBTITLE, ST["subtitle"]))
    story.append(Spacer(1, 5 * mm))

    t = Table([[""]], colWidths=[PAGE_W - MARGIN_L - MARGIN_R], rowHeights=[2.2])
    t.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, -1), WARM)]))
    story.append(t)
    story.append(Spacer(1, 9 * mm))

    story.append(Paragraph(
        "A developer's guide to the architecture, the measurement method, and the "
        "day-to-day operation of ThermalBench.", ST["lead"]))
    story.append(Spacer(1, 34 * mm))

    meta = Table(
        [["Version", VERSION],
         ["Generated", date.today().isoformat()],
         ["Audience", "Engineers building on, running, or reviewing ThermalBench"],
         ["Source", "GUIDE.md, and the code it describes"]],
        colWidths=[30 * mm, 110 * mm], hAlign="LEFT")
    meta.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (0, -1), "Helvetica-Bold"),
        ("FONTNAME", (1, 0), (1, -1), "Helvetica"),
        ("FONTSIZE", (0, 0), (-1, -1), 8.8),
        ("TEXTCOLOR", (0, 0), (0, -1), MUTED),
        ("TEXTCOLOR", (1, 0), (1, -1), INK),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
    ]))
    story.append(meta)
    story.append(NextPageTemplate("body"))
    story.append(PageBreak())


def table_of_contents(story):
    story.append(Paragraph("Contents", ST["h1"]))
    rows = []
    for i, sec in enumerate(DOCUMENT, start=1):
        rows.append([Paragraph(f"{i}", ST["tocnum"]),
                     Paragraph(sec["title"], ST["tocentry"])])
    t = Table(rows, colWidths=[10 * mm, 140 * mm], hAlign="LEFT")
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 1),
    ]))
    story.append(t)
    story.append(PageBreak())


# How many blocks may sit between a heading and the figure it introduces.
FIGURE_LEAD_BLOCKS = 2


# Room for the heading and a short introduction above a figure.
FIGURE_LEAD_SPACE = 30 * mm


def figure_image(path):
    """The image at the full text width."""
    image = Image(os.path.join(os.path.dirname(os.path.abspath(__file__)), path))
    width = PAGE_W - MARGIN_L - MARGIN_R
    image.drawHeight = image.imageHeight * width / image.imageWidth
    image.drawWidth = width
    return image


def figure_height(path):
    return figure_image(path).drawHeight


def figure(path, caption):
    """An image at the full text width, kept on one page with its caption."""
    image = figure_image(path)
    return KeepTogether([Spacer(1, 4), image, Spacer(1, 4), Paragraph(caption, ST["caption"])])


def render_block(block, story):
    kind = block[0]
    if kind == "p":
        story.append(Paragraph(block[1], ST["body"]))
    elif kind == "lead":
        story.append(Paragraph(block[1], ST["lead"]))
    elif kind == "h2":
        story.append(Paragraph(block[1], ST["h2"]))
    elif kind == "h3":
        story.append(Paragraph(block[1], ST["h3"]))
    elif kind == "code":
        story.append(code_block(block[1]))
        story.append(Spacer(1, 3))
    elif kind == "bullets":
        story.append(bullets(block[1]))
    elif kind == "steps":
        story.append(bullets(block[1], numbered=True))
    elif kind == "table":
        story.append(make_table(block[1], block[2] if len(block) > 2 else None,
                                block[3] if len(block) > 3 else ()))
        story.append(Spacer(1, 8))
    elif kind == "caption":
        story.append(Paragraph(block[1], ST["caption"]))
    elif kind == "note":
        story.append(callout(block[1], "accent"))
        story.append(Spacer(1, 5))
    elif kind == "warn":
        story.append(callout(block[1], "warn"))
        story.append(Spacer(1, 5))
    elif kind == "spacer":
        story.append(Spacer(1, block[1]))
    elif kind == "figure":
        story.append(figure(block[1], block[2]))
        story.append(Spacer(1, 6))
    else:
        raise ValueError(f"unknown block kind: {kind}")


def build_story():
    """Flowables are consumed by a build, so each pass gets a fresh story."""
    story = []
    cover(story)
    table_of_contents(story)

    for i, sec in enumerate(DOCUMENT, start=1):
        story.append(Paragraph(f"{i}. {sec['title']}", ST["h1"]))
        blocks = sec["blocks"]
        for j, block in enumerate(blocks):
            # A heading that introduces a figure starts a new page only when
            # the figure would not fit below it, rather than being left at the
            # foot of one page with its figure on the next.
            ahead = blocks[j + 1:j + 1 + FIGURE_LEAD_BLOCKS + 1]
            figures = [b for b in ahead if b[0] == "figure"]
            if block[0] in ("h2", "h3") and figures:
                story.append(CondPageBreak(figure_height(figures[0][1]) + FIGURE_LEAD_SPACE))
            render_block(block, story)
    return story


def main():
    out_dir = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(out_dir, OUTPUT_NAME)
    scratch = os.path.join(out_dir, ".pass1-" + OUTPUT_NAME)

    meta = dict(title=TITLE, author="ThermalBench",
                subject=SUBTITLE, creator="ThermalBench docs build")

    # Pass one: discover where each section starts.
    probe = ManualDoc(scratch, **meta)
    probe.build(build_story())
    sections = probe.resolved_sections(probe.page)
    if os.path.exists(scratch):
        os.remove(scratch)

    # Pass two: draw the real document with correct running headers.
    doc = ManualDoc(path, section_map=sections, **meta)
    doc.build(build_story())

    size = os.path.getsize(path)
    print(f"wrote {path} ({doc.page} pages, {size/1024:.0f} KB)")


if __name__ == "__main__":
    main()
