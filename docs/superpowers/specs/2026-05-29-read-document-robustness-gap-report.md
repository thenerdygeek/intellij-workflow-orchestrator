# read_document Extraction-Robustness Gap Report

**Date:** 2026-05-29
**Scope:** 21-file multi-format corpus (DOCX, PPTX, XLSX, PDF, HTML, CSV, JSON, MD) extracted via the `read_document` pipeline; all findings below were adversarially verified against the raw source bytes (OOXML parts, `pdftotext`/`pdfinfo`/`qpdf` decompression, byte offsets).

## 1. Executive summary

`read_document` reliably **lexes text** — every file in the corpus extracted with `status=ok` and the bulk of prose survives — but it is **not yet trustworthy for structured, navigable, or verbatim use**, and the failure profile is sharply format-dependent. **Plaintext-derived formats (HTML/MD/JSON) and simple OOXML (DOCX/XLSX/PPTX) are mostly faithful** for flat content, while **complex multi-column PDFs are the weakest format by a wide margin**: every large PDF (NIST 800-53r5/63-3/63b/CSF, BJS, Fed SCF, arXiv, RFC 7230) exhibits at least one *critical* defect.

The top systemic gaps, in order of blast radius:

1. **PDF page anchors are broken** — five PDFs dump 70-100% of the body under a single `page 1` marker, leaving pages 2-N as empty markers. Page-based citation/navigation is unusable for the majority of every large PDF.
2. **Tables are silently destroyed** — lattice/grouped-header tables across PDF and DOCX are flattened, column-misaligned, truncated (leading-digit drops), or reduced to bare captions. The most operationally important tables in NIST 800-53/63 and BJS (control summaries, IAL/AAL grids, victimization counts) are *lost or corrupted while presenting as authoritative*.
3. **A whitespace-injection bug corrupts URLs verbatim** in HTML/MD/JSON code blocks (`" https://..."`), silently breaking copy-paste config and OAuth URLs while keeping JSON syntactically valid.
4. **HTML is read with the wrong charset** — UTF-8 decoded as CP1252 produces pervasive mojibake AND drifts every downstream byte offset (which cascades into broken section anchors).
5. **No cross-reference graph survives** — PDF/PPTX hyperlinks (7,692 link annots in 800-53r5 alone), DOCX internal anchors, and all `[2]`/`#cite_note`/`Section N`/`Table N` references are flattened to dead text, while DOCX/XLSX preserve their links better.
6. **Images/figures carry no alt-text/captions** and reference non-portable `/var/folders/.../T/` temp paths; figure-to-caption association is universally lost; the `imageMarkers` metric is self-contradictory with the body.
7. **Heading detection is both noisy and lossy** — body sentences/acronyms/equation glyphs get promoted to headings while real numbered sections/subsections are dropped, polluting the only navigation index.

**Total confirmed findings: 125** across 21 files (every finding adversarially verified against raw source), spanning critical/high/medium/low severities. The critical tier is dominated by broken PDF page anchors, destroyed/corrupted tables, the verbatim whitespace-injection bug, HTML mojibake, and the XLSX merge-spill data fabrication.

## 2. Corpus inventory

| file | ext | status | metrics |
|---|---|---|---|
| docx-calibre-demo.docx | docx | ok | sec=17 pg=0 tbl=39 img=4 url=1 chars=11092 |
| docx-doxx-comprehensive.docx | docx | ok | sec=13 pg=0 tbl=5 img=0 url=0 chars=1287 |
| docx-doxx-images.docx | docx | ok | sec=2 pg=0 tbl=0 img=3 url=0 chars=577 |
| html-csv-json-schemastore-readme.md | md | ok | sec=0 pg=0 tbl=0 img=0 url=8 chars=2317 |
| html-csv-json-swagger-petstore.json | json | ok | sec=0 pg=0 tbl=0 img=0 url=9 chars=13852 |
| html-csv-json-titanic.csv | csv | ok | sec=0 pg=0 tbl=893 img=0 url=0 chars=191103 |
| html-csv-json-wikipedia-gdp.html | html | ok | sec=12 pg=0 tbl=268 img=0 url=109 chars=143170 |
| nist-800-53r5.pdf | pdf | ok | sec=456 pg=492 tbl=2028 img=498 url=509 chars=2054623 |
| nist-800-63-3.pdf | pdf | ok | sec=83 pg=76 tbl=1237 img=15 url=169 chars=398769 |
| nist-800-63b.pdf | pdf | ok | sec=131 pg=80 tbl=137 img=69 url=190 chars=231535 |
| nist-csf.pdf | pdf | ok | sec=33 pg=55 tbl=377 img=3 url=46 chars=346934 |
| pdf-rich-arxiv-attention.pdf | pdf | ok | sec=27 pg=15 tbl=22 img=3 url=1 chars=44964 |
| pdf-rich-bjs-cv22.pdf | pdf | ok | sec=58 pg=34 tbl=944 img=0 url=15 chars=231537 |
| pdf-rich-fed-scf.pdf | pdf | ok | sec=53 pg=58 tbl=51 img=0 url=30 chars=144715 |
| pptx-poi-links.pptx | pptx | ok | sec=6 pg=0 tbl=16 img=1 url=1 chars=1120 |
| pptx-poi-notes.pptx | pptx | ok | sec=11 pg=0 tbl=0 img=0 url=0 chars=2632 |
| pptx-poi-smartart.pptx | pptx | ok | sec=1 pg=0 tbl=0 img=0 url=0 chars=37 |
| rfc7230.pdf | pdf | ok | sec=95 pg=89 tbl=0 img=0 url=24 chars=196070 |
| xlsx-excelize-book1.xlsx | xlsx | ok | sec=2 pg=0 tbl=34 img=0 url=1 chars=1362 |
| xlsx-excelize-calcchain.xlsx | xlsx | ok | sec=1 pg=0 tbl=2 img=0 url=0 chars=40 |
| xlsx-excelize-mergecell.xlsx | xlsx | ok | sec=1 pg=0 tbl=13 img=0 url=0 chars=350 |

## 3. Findings by concern dimension

Within each dimension, findings that are the *same systemic defect across files* are collapsed into one entry with an "Affected files" list. File-specific findings are listed individually.

---

### 3.1 Tables

**T-1 — [CRITICAL] Lattice / grouped-header data tables flattened, column-misaligned, or reduced to bare captions.** *Systemic across all complex PDFs and DOCX.*
- **Affected files:** docx-calibre-demo.docx (calendar grid → 1 garbage column; nested table dropped), nist-800-53r5.pdf (20 Control Summary Tables C-1..C-20 = titles only), nist-800-63-3.pdf (Tables 5-1/5-2/5-3/6-1 = captions only; Abbreviations Table A.2 lost), nist-800-63b.pdf (Tables 2-1/4-1/8-1/8-2 dropped, captions survive), pdf-rich-arxiv-attention.pdf (Table 2 grouped BLEU/Cost headers destroyed; Table 3 ablation grid fused), pdf-rich-bjs-cv22.pdf (Table 1 11→6 cols, daggers in data cells), pdf-rich-fed-scf.pdf (Table 1/2 row-group headers dropped; pipe-table header-only with all data rows missing).
- **Evidence (representative):** 800-53r5 content.md emits `TABLE C-1: ACCESS CONTROL FAMILY` followed by a blank line, while raw page 456 is a populated 4-col grid (`AC-1 | Policy and Procedures | O | √`). Calibre calendar (raw 14-gridCol Sun..Sat, dates 1-31) collapses to `December 2007 / Sun / 2 / 9 / 16 / 23 / 30` — only Sundays survive.
- **Impact:** The single most-used reference matrices (control summaries, IAL/AAL/FAL grids, victimization counts) are missing or wrong **while presenting as complete**, so an LLM answers from the wrong data or hallucinates.

**T-2 — [CRITICAL] Leading-digit / first-character truncation of cells in PDF lattice tables.**
- **Affected files:** pdf-rich-bjs-cv22.pdf (`4,558,15` for 4,558,150; years `994`/`006`; row labels `ale`/`emale`/`hitec`), nist-800-53r5.pdf errata (`ontrol`, `ection`, `aselines`), nist-800-63b.pdf author/cover blocks (`Elaine M. Newto`, `ndrew R. Regenschei`).
- **Evidence:** BJS Appendix Table 1 years render `994`...`022`; raw is `1994`...`2022`. Column-boundary detection clips the first glyph of each cell into the previous (empty) column.
- **Impact:** Numbers are *factually corrupted* (`16.` ≠ `16.4`); labels become unsearchable.

**T-3 — [CRITICAL] Same table emitted twice with conflicting numbers (reflow copy + lattice copy).**
- **Affected files:** pdf-rich-bjs-cv22.pdf.
- **Evidence:** Table 1 value `6,385,520` appears at line 247 (reflow, under page-1 marker) and again at line 1259 (24-col lattice, under page-3 marker); same for Tables 2,4,5,6,11-16. Doubles `pipeTableRows=944` with garbage.
- **Impact:** Two contradictory copies, no authoritative signal.

**T-4 — [CRITICAL] `%`/significance symbols displace actual numeric values.**
- **Affected files:** pdf-rich-bjs-cv22.pdf (Table 4: `45.6 | %`, `Assault | 46.0 | ‡` where raw 2022 = 41.5%, 40.6).
- **Impact:** "What percent of assaults reported to police in 2022?" returns `‡`.

**T-5 — [HIGH] Errata / change-history tables badly garbled (phantom columns, duplicated+truncated cells, interleaved noise).**
- **Affected files:** nist-800-53r5.pdf (`10.63042 8/N` page cell), nist-800-63-3.pdf (`| | Date | | | Type |...` dup/shifted), nist-800-63b.pdf (12-col, `Editorial`/`ditorial`, scrambled `liicmaptieorns`), nist-csf.pdf (NTR-1 2-col → malformed 6-col; Table 1 RC.IM/RC.CO column shift).
- **Impact:** Change provenance unreadable; dates detached from revisions.

**T-6 — [HIGH] Multi-page table fragmentation: repeated/doubled headers, no continuation marker, fragments mis-attached to wrong heading.**
- **Affected files:** nist-csf.pdf (Glossary Table 3 split with Supplier/Taxonomy duplicated under wrong heading; Framework Core header repeated ~19× plus doubled `Function|Function` columns).
- **Impact:** Glossary appears to define only 2 of ~24 terms; Core looks like many separate tables.

**T-7 — [MEDIUM] Vector charts reduced to bare titles; chart data/legend lost (`imageMarkers=0`).**
- **Affected files:** pdf-rich-bjs-cv22.pdf (Figure 1/2 line charts), pdf-rich-fed-scf.pdf (Figure 1/2 bar charts — also caption glued into prose).
- **Note:** `imageMarkers=0` is *correct* (vector charts, no raster XObjects); the loss is the axis/legend text and any figure placeholder.

**T-8 — [MEDIUM] Pipe-table metric semantics overstate row counts.**
- **Affected files:** html-csv-json-titanic.csv (`pipeTableRows=893` = 891 data + header + `---` separator; true data rows = 891).

**T-9 — [MEDIUM] Prose wrapped as single-column pipe tables, fragmenting paragraphs.**
- **Affected files:** nist-800-63b.pdf (boilerplate paragraphs rendered as 1-col table rows with word-joining; inflates `tableRows=137`).

**T-10 — [LOW] DOCX/PPTX/XLSX merge structure not expressible / not represented in markdown.**
- **Affected files:** pptx-poi-links.pptx (slide-4 merged cells → blank trailing cells), html-csv-json-wikipedia-gdp.html (sr-only `N/a` merged with em-dash → `âN/a`).
- (See P-7 for the XLSX merge-spill *critical* variant, which is a data-fabrication defect, not just a representation gap.)

---

### 3.2 Images / figures

**IMG-1 — [HIGH/MEDIUM] Image markers carry no alt-text/caption and use non-portable absolute `/var/folders/.../T/` temp paths.** *Systemic across every format with images.*
- **Affected files:** docx-doxx-images.docx (drops docPr `title`/`descr`: "Photo of boulders...", "Milky way galaxy...", "Abacus with solid fill"), nist-800-53r5.pdf, nist-800-63-3.pdf, nist-csf.pdf, pptx-poi-links.pptx, xlsx-excelize-book1.xlsx (and the figure-association loss below).
- **Evidence:** `[image: /var/folders/ny/_5zhvy.../T/workflow-document-images/document-3c82bb/image-ba99a6.jpg] (image/jpeg)` — the only machine-readable figure description in the source (alt-text) is discarded.
- **Impact:** Figures are uninterpretable, unsearchable, and the path will not exist for any downstream consumer.

**IMG-2 — [HIGH] Figures stranded away from their captions / wrong page; figure→caption mapping lost.**
- **Affected files:** nist-csf.pdf (Figs 1/2/3 markers in empty page-13/19/24 region, captions hundreds of lines away under page 1), nist-800-53r5.pdf ("Figure 1 illustrates..." with no diagram/caption), nist-800-63-3.pdf (Figure 4-1/6-1/6-2/6-3 captions only in TOC/prose).

**IMG-3 — [HIGH/MEDIUM] Embedded objects / SmartArt / shapes silently dropped or flattened with no figure marker.**
- **Affected files:** pptx-poi-smartart.pptx (SmartArt AlternatingHexagons → 4 plain bullets, `imageMarkers=0`), docx-doxx-images.docx (SmartArt → fake bullet list; shape "Direct Access Storage 1" dropped entirely), pptx-poi-notes.pptx (slide 8 full-canvas embedded `PowerPoint.Slide.8` OLE object invisible; 2 images + 3 OLE objects absent), xlsx-excelize-book1.xlsx (embedded JPEG not surfaced, `imageMarkers=0`).
- **Impact:** Slides/docs look near-empty when dominated by a diagram; lead-in prose ("shapes and SmartArt") is unfulfilled.

**IMG-4 — [HIGH] `imageMarkers` metric contradicts the body.**
- **Affected files:** html-csv-json-wikipedia-gdp.html (`imageMarkers=0` but 239 `[embedded:]` markers matching 239 raw `<img>` tags).
- **Impact:** A planner trusting the metric concludes "nothing visual" while the body is cluttered with markers.

**IMG-5 — [MEDIUM] Decorative inline icons dumped as a contiguous wall divorced from their row context.**
- **Affected files:** html-csv-json-wikipedia-gdp.html (216 bare `40px-Flag_*` markers emitted as one block before the data table, no alt/country association).

**IMG-6 — [MEDIUM] Sub-threshold decorative glyph fragments emitted as image markers (no real figures).**
- **Affected files:** nist-800-63b.pdf (69 sub-1KB rasterized glyph PNGs marked as images; document has no real body figures).

---

### 3.3 Hyperlinks & cross-references

**HX-1 — [CRITICAL/HIGH] All link annotations dropped; internal cross-references collapse to dead text; PDF/PPTX worst, DOCX/XLSX preserve more.** *Systemic.*
- **Affected files:** nist-800-53r5.pdf (7,692 `/Link` annots lost, 0 markdown links; "Quick link to..." → H1; "Related Controls:..." plain), nist-800-63b.pdf (link-anchor *hoisting* corrupts meaning: `(Section 5.1.7Section 5.1.1) ... ()`, `FIPS 140FIPS 140FIPS 140FIPS 140`, `[RFC 20ISO/ISC 10646] ... []`), pdf-rich-arxiv-attention.pdf (113 internal + 36 URI links → only github URL survives), pdf-rich-bjs-cv22.pdf (27 `/Link`, 32 `/URI`, `(table 1)` glued, 0 markdown links), pdf-rich-fed-scf.pdf (173 `/Link`, 106 `/URI` dropped), nist-csf.pdf (113 `/Link` internal refs plain + fused `SeeAppendix Afor`), docx-calibre-demo.docx (17 TOC anchors + 18 internal anchors flattened to plain text — **MEDIUM**, anchor-flattening is expected for markdown), pptx-poi-links.pptx (3 hlinkClick external/slide-jump/mailto → plain text, types lost).
- **Evidence (most severe):** 800-63b line 410 `(Section 5.1.7Section 5.1.1) ... Memorized Secret ()` — anchors merge at first occurrence leaving empty `()`/`[]`; **meaning is reversed in a normative security standard** (cannot tell which FIPS level applies to which clause).
- **Impact:** The document's internal navigation graph (control↔control, citation↔reference, figure/table refs) is entirely flattened; 800-63b additionally *corrupts* normative meaning.

**HX-2 — [CRITICAL/HIGH] Dead/empty footnote & citation anchors; `#cite_note` targets and `[2]` labels do not resolve.**
- **Affected files:** html-csv-json-wikipedia-gdp.html (56 `(#cite_note-...)`/`(#cite_ref-...)` tokens, no matching anchor targets), docx-calibre-demo.docx (footnote+endnote both labelled `[^2]` — invalid collision; raw `footnoteRef:2`/`endnoteRef:2` tokens leaked into body).

**HX-3 — [MEDIUM/LOW] External URLs duplicated as `text (url)` or fabricated inline; redundant/malformed parentheticals.**
- **Affected files:** nist-800-53r5.pdf (`https://doi.org/...r5 (https://doi.org/...r5)` self-duplicating), pdf-rich-bjs-cv22.pdf (`...datadocumentation))` stray `)`), nist-csf.pdf (`(https://...)to`, `the2016`, `and2017` fused), docx-calibre-demo.docx (URL inlined into body text that did not contain it — alters literal text).

**HX-4 — [HIGH] URLs corrupted by injected `<br>` mid-URL, split fragments, glued punctuation.**
- **Affected files:** nist-800-63-3.pdf (`.../final<br>https://www.nist.gov/...publication-<br>800-63-...`, truncated `https://doi.org/10.6028/<br>Step`).
- **Note:** the PDF carries clean per-annotation `/URI` targets that are not used; reflowed visible text is captured instead. (See WS-1 for the leading-space-in-URL variant.)

---

### 3.4 Search / navigation

**NAV-1 — [CRITICAL] PDF page anchors collapse most of the body under `page 1`; pages 2-N are empty markers.** *Systemic across large PDFs — the single highest-blast-radius defect.*
- **Affected files:** nist-800-53r5.pdf (~75% of lines under page 1; pages 20-492 point at image-marker noise), nist-800-63-3.pdf (pages 53-76 each +18 chars apart = empty; glossary unnavigable), nist-800-63b.pdf (entire body under page 1; off 6733-199617), nist-csf.pdf (pages 3-30 ≈ 870 bytes total while raw pages hold ~3,800 chars each), pdf-rich-bjs-cv22.pdf (entire reflowed body under page 1; pages 2-23 ~17-18 chars apart).
- **Evidence:** 800-53r5 `pages.txt` shows page 1 at off=16804, page 2 at off=1427131 — ~12,800 of 16,966 lines under page 1.
- **Impact:** Page-based citation/navigation is **wrong for the bulk of every large PDF**; "jump to page N" lands in empty markers or scrambled back-matter.

**NAV-2 — [CRITICAL] Section-anchor byte offsets are inaccurate and drift progressively (HTML), computed against a different byte stream than final content.md.**
- **Affected files:** html-csv-json-wikipedia-gdp.html (`## Table` claimed 22355 but at 23178; `References` claimed 126461 lands mid-footnote; drift correlates with mojibake byte-expansion).
- **Impact:** Every anchor past the language list points to the wrong place — the only machine-navigable index is unusable.

**NAV-3 — [HIGH] Heading detection promotes non-headings (UI chrome, body sentences, acronyms, equation glyphs, figure labels) into section anchors.** *Systemic.*
- **Affected files:** html-csv-json-wikipedia-gdp.html (6/12 anchors are Wikipedia sidebar chrome — Contribute/Appearance/English/...), nist-800-63-3.pdf (~300-word body paragraphs as H1, anchors 19/20/21), nist-800-63b.pdf (`#### For a variety of reasons...` sentence as anchor #63, `Cookies:` #86), nist-csf.pdf (18 Appendix-C acronyms each H1, anchors 16-33), pdf-rich-fed-scf.pdf (21/53 anchors are `N Changes in U.S. Family Finances` running headers; chart-axis fragments `100 Percent`, `4.75 Ratio...`; `Urbanicity` twice), pdf-rich-arxiv-attention.pdf (anchor `QKT` = equation numerator, `<EOS> <EOS>...` = heatmap labels).
- **Impact:** "List the sections" returns UI menus / sentence fragments / acronyms; the navigation index is both noisy and misleading.

**NAV-4 — [HIGH/CRITICAL] Real numbered headings/subsections dropped; bodies misfiled under wrong parent.** *Systemic — the inverse of NAV-3.*
- **Affected files:** nist-csf.pdf (Section 4.0 missing, body under 3.6; subsections 1.2/3.2/3.3/3.4/3.7/2.1-2.4 dropped — **CRITICAL/HIGH**), nist-800-63-3.pdf (Executive Summary, 1 Purpose, 4, 5, 5.1/5.3/5.5, 7, Appendix A/A.1/A.2 missing), rfc7230.pdf (2.7.x, 5.3.x, A.1.x, A.2 missing while peers captured), pdf-rich-bjs-cv22.pdf (anchors concatenate multiple table titles — Table 12+13, Appendix 11+10+9).
- **Impact:** Whole major divisions are un-locatable; the LLM may conclude they don't exist or quote the wrong heading.

**NAV-5 — [LOW] No section anchors emitted for ATX/markdown headings or for non-sheet objects.**
- **Affected files:** html-csv-json-schemastore-readme.md (`sectionAnchors=0` despite 7 ATX headings), xlsx-excelize-book1.xlsx (anchors cover only sheet names, not charts/Table1/image/comment).

**NAV-6 — [HIGH] Heading hierarchy flattened/inverted (controls H1 above their parent H2; no H3+).**
- **Affected files:** nist-800-53r5.pdf (426 H1 / 30 H2 / 0 H3; `# AC-24` outranks `## 3.1 ACCESS CONTROL`).

---

### 3.5 Structure fidelity (TOC, reading order, spacing, footnotes, page furniture)

**SF-1 — [CRITICAL/HIGH] PDF reading order scrambled: text from pages hundreds apart stitched together; two-column layouts interleaved line-by-line.** *Systemic.*
- **Affected files:** nist-800-53r5.pdf (Chapter-3 AU-4 injected mid-Chapter-2; running-header page numbers leap 193,56,284,375,...), pdf-rich-fed-scf.pdf (**CRITICAL** — chart axis labels `40/35/30/25/20...` spliced into prose: `real15 median net worth surged 37 percent to 10`), nist-800-63b.pdf (footer DOI string woven into body/errata cells), pdf-rich-arxiv-attention.pdf (page-13 figure header char-interleaved `AInttpenutito-nInVipsuuatli zLataioynes r5`).
- **Impact:** Adjacent paragraphs are unrelated; sentences corrupted; chart values hallucinated as narrative claims.

**SF-2 — [CRITICAL/HIGH] Preformatted/monospace blocks (ABNF grammar, pseudo-code, message examples, ASCII-art) reflowed onto single lines.**
- **Affected files:** rfc7230.pdf (ABNF productions merged onto one line — line-as-record-separator destroyed; chunked-decoding pseudo-code run-on; HTTP message examples collapsed; ASCII intermediary-chain diagram `> > > > UA===A===B===C===O < < < <` flattened), pdf-rich-arxiv-attention.pdf (display Eq.(1) shredded: `# QKT` heading, empty `softmax( √ )`, `(1)dk`).
- **Impact:** The primary value of a protocol grammar / a paper's defining formula is unreadable.

**SF-3 — [HIGH/MEDIUM] Table-of-Contents collapsed into one run-on line; page numbers fused to next entry; dot leaders retained.** *Systemic.*
- **Affected files:** nist-800-53r5.pdf, nist-800-63-3.pdf (`...Flexibilities ... 52.3A Few Limitations`), nist-800-63b.pdf (`84.3.1`, `94.3.2` fused), nist-csf.pdf, pdf-rich-fed-scf.pdf (`Income .... 5Income by Family Characteristics`), docx-calibre-demo.docx (TOC `Title\tN` lines with stale page numbers while `pageAnchors=0`).
- **Impact:** TOC unparseable as a list; page references ambiguous/wrong.

**SF-4 — [HIGH/MEDIUM] Pervasive lost inter-word spacing / glued tokens at style/link boundaries; dropped bullet glyphs (U+FFFD).** *Systemic.*
- **Affected files:** nist-800-63-3.pdf (`aclaimantand`, `inFigure 4-1`, `See alsoAsymmetric KeysSymmetric Key`), pdf-rich-bjs-cv22.pdf (`theUnited States`, `victimizationsper`; every HIGHLIGHTS bullet `- �` = U+FFFD), nist-csf.pdf (`SeeAppendix Afor`, `the2016`).
- **Impact:** Full-text search misses matches; `�` looks like a decoding failure.

**SF-5 — [MEDIUM] Repeating running headers/footers retained as body content.** *Systemic.*
- **Affected files:** nist-800-53r5.pdf (~465 lines), rfc7230.pdf (89 `Fielding & Reschke ... [Page N]`), nist-800-63-3.pdf / nist-800-63b.pdf (DOI furniture), pdf-rich-fed-scf.pdf (running-header text became section anchors — see NAV-3).
- **Impact:** Boilerplate bloats context, fragments paragraphs, inflates URL counts.

**SF-6 — [CRITICAL/MEDIUM] HTML decoded as Latin-1/CP1252 — pervasive mojibake; sr-only text concatenated; trailing chrome duplicated.**
- **Affected files:** html-csv-json-wikipedia-gdp.html (`EspaÃ±ol`, `TÃ¼rkÃ§e`, `SÃ£o TomÃ©`, `$10â20 trillion`; `âN/a`; `79 languages`/`Add topic` footer chrome). Also the root cause of NAV-2 offset drift.
- **Impact:** Every multibyte glyph corrupted; place/language names unreproducible; byte offsets inflated.

**SF-7 — [MEDIUM/LOW] Footnote markers leak raw OOXML tokens / glue to preceding word; label collisions.**
- **Affected files:** docx-calibre-demo.docx (`[footnoteRef:2]`/`[endnoteRef:2]` token leak; `[^2]` collision — see HX-2), pdf-rich-fed-scf.pdf (`form.6`, `economy.8`, `surveys.10` superscripts glued).

**SF-8 — [MEDIUM/LOW] Author/masthead/letterhead blocks linearized, losing column grouping.**
- **Affected files:** rfc7230.pdf (two-column masthead fused: `R. Fielding, Ed.Request for Comments: 7230`), pdf-rich-arxiv-attention.pdf (`Jakob Uszkoreit∗Google Brain`), nist-800-63b.pdf (author blocks with dropped leading letters — see T-2).

**SF-9 — [HIGH] Document title is a tool artifact, not the real title.**
- **Affected files:** rfc7230.pdf (`Title: Enscript Output` + `# Enscript Output` H1; real title demoted to body paragraph).

**SF-10 — [MEDIUM] Paragraphs split into one-line-per-visual-line blocks; line-wrap hyphens not rejoined.**
- **Affected files:** pdf-rich-fed-scf.pdf (`Gov-` + `ernors`, `assis-` + `tance` split across blank-line paragraphs).

**SF-11 — [LOW] In-quote / trailing whitespace trimmed (CSV byte-fidelity loss).**
- **Affected files:** html-csv-json-titanic.csv (`Kingcome) ` → `Kingcome)`).

**SF-12 — [HIGH] PPTX title placeholder silently dropped.**
- **Affected files:** pptx-poi-links.pptx (slide-2 `PPTX Title` ctrTitle absent; output starts at subtitle).

---

### 3.6 JSON / code blocks

**JC-1 — [HIGH] Leading space injected into URL/string values inside fenced code & JSON, silently breaking copy-verbatim config while keeping JSON valid.** *(Same root tokenizer bug as WS-1 below — see Systemic Gaps.)*
- **Affected files:** html-csv-json-schemastore-readme.md (`"url": " https://mcp.schemastore.org/"` in both ```json blocks; also `** https://...**` and `` ` https://...` ``), html-csv-json-swagger-petstore.json (7 URL values + 1 markdown-link text get a leading space; `authorizationUrl` for OAuth2 corrupted; both raw and extracted parse so the mutation is silent).
- **Impact:** An LLM reproducing the config emits a malformed URL that fails connection/validation; no validator flags it because JSON still parses.

**JC-2 — [HIGH] Display equation / math layout destroyed (numerator → heading, empty radicand, glued eq number).**
- **Affected files:** pdf-rich-arxiv-attention.pdf (Eq.(1) `# QKT` / `softmax( √ )V (1)dk`). (Cross-listed with SF-2.)

---

### 3.7 Per-format correctness (XLSX formulas/values/merges)

**P-1 — [HIGH] XLSX formulas dropped; only cached value shown.** *Systemic across XLSX.*
- **Affected files:** xlsx-excelize-book1.xlsx (`SUM(Sheet2!D2,Sheet2!D11)` → bare `237`; Sheet2 row-11 `B2+B3` and shared `IF(...)` formulas dropped), xlsx-excelize-calcchain.xlsx (`SUM(C1:D1)` → cached values, calcChain invisible).
- **Impact:** Cross-sheet dependencies invisible; the fixture's primary feature untested; LLM treats computed totals as hand-entered constants.

**P-2 — [HIGH] XLSX silent value corruption — cached value misreported.**
- **Affected files:** xlsx-excelize-calcchain.xlsx (B1 `<v>1</v>` rendered as `0` → `| 0 | 0 |`).
- **Impact:** Factually wrong data, not just omission.

**P-3 — [CRITICAL] Merged-cell anchor value duplicated into every cell of the merge range — fabricates data in empty cells.**
- **Affected files:** xlsx-excelize-mergecell.xlsx (`A7` fills a 3×4 block = 12 cells when it exists once; 14+ phantom data points).
- **Impact:** Cardinality, sums, "which cells contain data" all wrong.

**P-4 — [HIGH] No representation that cells are merged; merge structure silently flattened.**
- **Affected files:** xlsx-excelize-mergecell.xlsx (4 merge ranges A1:B1/A2:A3/A4:B5/A7:C10 → flat 4-col grid, no markers).

**P-5 — [HIGH/MEDIUM] First data row wrongly promoted to a markdown table header.** *Systemic across XLSX with no real header.*
- **Affected files:** xlsx-excelize-mergecell.xlsx (`A1/C1/D1` → header + `---`, 12 rows → 1 header + 11 body), xlsx-excelize-calcchain.xlsx (sole data row `| 0 | 0 |` → header).

**P-6 — [HIGH] Defined Excel table (ListObject) and its header cells entirely missing.**
- **Affected files:** xlsx-excelize-book1.xlsx (Table1 C21:D26 with headers Column1/Column2 absent from output).

**P-7 — [MEDIUM] Sparse sheet grid not rendered faithfully (cell addresses discarded, dense table fabricated); charts labeled generically.**
- **Affected files:** xlsx-excelize-book1.xlsx (sparse A19:D22 → fabricated dense 2-col table; two charts both `**Chart**`, doughnut/bar type + Sheet2 provenance lost).

**P-8 — [MEDIUM] Printed page labels conflict with physical page anchors (three unreconciled numbering systems).**
- **Affected files:** nist-800-53r5.pdf (`<!-- page: 19 -->` while raw prints roman `xvii`; body `PAGE 429` = physical 456).

**P-9 — [LOW] DOCX `pageCount` reported `-` although docProps declares it.**
- **Affected files:** docx-calibre-demo.docx (`<Pages>3</Pages>` dropped; unreconcilable with extracted TOC page 8).

---

## 4. Systemic gaps & recommended fixes

### G-1 — PDF page-offset assignment is fundamentally broken (P0)
**Theme:** NAV-1 — all glyphs attributed to `page 1`, trailing pages get empty markers; page anchors 20-N point at image/header noise; printed vs physical labels unreconciled (P-8).
**Fix direction:** Re-architect the PDF page pipeline to emit page markers at *true* PDF page boundaries (per-page extraction order, assign each text run to the page it physically renders on). Record both physical index and printed page label per page and disambiguate in anchors (`page 19 / printed xvii`). This is the highest-leverage fix — it unblocks page citation across the entire PDF corpus and is a precondition for figure-on-correct-page placement (IMG-2).
**Priority:** **P0.**

### G-2 — Whitespace-injection tokenizer corrupts verbatim content (P0)
**Theme:** JC-1 + WS-1 (`" https://..."` in JSON/MD/fenced code) and HX-4/`< http://...` in RFC. A normalizer pads tokens beginning with a URL scheme (or following `**`/`` ` ``/`<`) with a leading space.
**Fix direction:** For `json`/`code`/fenced-block content, emit source bytes **verbatim** — never normalize whitespace inside string literals or code fences. Fix the autolink/URL tokenizer so it does not insert a separator before URL spans. Audit every URL-bearing fixture for the same mutation.
**Priority:** **P0** (silent, syntactically-valid corruption is the most dangerous class).

### G-3 — Table reconstruction loses or corrupts structured data (P0)
**Theme:** T-1..T-6 — lattice/grouped-header detection drops body rows (captions only), misaligns columns, clips leading characters, emits phantom columns, double-renders (reflow + lattice), and merges significance/unit symbols into data cells.
**Fix direction:** Reconstruct column boundaries from PDF text x-coordinates (not left-to-right flow); detect merged/spanned/grouped headers and flatten to composite leaf headers (`Mean income 2022`); fill inherited base cells; never clip the first glyph of a cell into the previous column; keep unit/significance symbols in their own columns; emit each table **once** in reading order; mark page-spanning fragments as continuations and de-duplicate repeated headers.
**Priority:** **P0** for the data-corruption variants (T-1/T-2/T-3/T-4); **P1** for fragmentation/metric (T-5/T-6/T-8/T-9).

### G-4 — HTML charset detection is wrong, and offsets are computed pre-normalization (P1)
**Theme:** SF-6 (UTF-8 read as CP1252) cascades into NAV-2 (offset drift).
**Fix direction:** Honor `<meta charset>`/HTTP charset (read HTML as UTF-8) before handing bytes to Tika. Compute *all* section/page offsets against the **final emitted content.md bytes** (after charset normalization), or store anchors as line numbers / regex anchors instead of raw byte offsets.
**Priority:** **P1** (affects HTML class; offset-after-normalization is a general invariant worth enforcing for all formats).

### G-5 — Heading detection is both over- and under-inclusive (P1)
**Theme:** NAV-3 (promotes chrome/sentences/acronyms/equation glyphs/figure labels) + NAV-4 (drops real numbered sections) + NAV-6 (inverted hierarchy) + SF-9 (tool-artifact title).
**Fix direction:** Constrain heading promotion with length/format guards (reject multi-sentence or >~120-char lines, lines ending in `:`, equation glyphs, figure labels); seed/cross-check anchors from the **bookmark/outline tree** already present (map Chapter=H1, N.x=H2, control=H3); strip nav/boilerplate regions (Boilerpipe/readability) for HTML; prefer the first prominent text line over known generator `/Title` artifacts.
**Priority:** **P1.**

### G-6 — No cross-reference graph survives; PDF/PPTX links dropped, DOCX/XLSX better (P1)
**Theme:** HX-1/HX-2/HX-4 — `/Link`+`/URI` annotations discarded (or anchor-hoisted, corrupting meaning in 800-63b), `#cite_note`/`[^2]`/`Section N`/`Table N` references dead.
**Fix direction:** Harvest links from `/URI` and `/Link` annotations (not reflowed visible text), place each link's display text at its own glyph position (fix the anchor-hoisting that merges all paragraph links at the first anchor), and emit markdown anchors targeting section/table anchors for internal refs. Namespace footnote vs endnote labels. **Note the format asymmetry:** DOCX/XLSX retain link semantics better than PDF/PPTX — close the gap.
**Priority:** **P1** (HX-1 corruption variant in 800-63b is **P0**-adjacent because it *reverses normative meaning*).

### G-7 — Images/figures have no semantic anchor (P1/P2)
**Theme:** IMG-1..IMG-6 — no alt-text/captions, non-portable temp paths, stranded from captions, embedded objects/SmartArt dropped, contradictory `imageMarkers` metric, decorative-fragment noise.
**Fix direction:** Emit docPr/cNvPr `title`/`descr` (DOCX/PPTX) and nearest figure caption (PDF) as marker alt-text; use stable relative asset paths; place markers at the figure's real reading position; emit a placeholder for SmartArt/shapes/OLE objects (`[SmartArt: AlternatingHexagons]`, `[Embedded object: PowerPoint.Slide.8]`); size/area-filter sub-threshold decorative glyphs; reconcile `imageMarkers` with the actually-emitted markers.
**Priority:** **P1** for the metric contradiction (IMG-4) and load-bearing object drops (IMG-3); **P2** for temp-path/caption polish.

### G-8 — XLSX formulas, merges, and header detection misrepresent sheet semantics (P1)
**Theme:** P-1..P-7 — formulas dropped (and one value silently corrupted, P-2), merge anchor value fabricated across the range (P-3, critical), no merge markers, first data row forced into a header, defined tables missing, sparse grids fabricated.
**Fix direction:** Emit `<f>` formula text alongside cached value (`=SUM(Sheet2!D2,Sheet2!D11) (237)`); verify `<v>`→column mapping (fix the B1→0 corruption); for merges, write the value only in the anchor and leave spanned cells blank plus a `Merged ranges: ...` note; don't promote row 1 to a header without header semantics; render defined tables/charts/images/comments and register them as anchors; preserve cell addresses for sparse sheets.
**Priority:** **P1** (P-3 data fabrication and P-2 value corruption are the urgent items within this theme).

### G-9 — Preformatted/monospace fidelity and reading order (P1)
**Theme:** SF-1 (reading order scrambled, two-column interleave), SF-2/JC-2 (ABNF/pseudo-code/ASCII-art/equations reflowed), SF-3 (TOC run-on), SF-4 (lost spacing), SF-5 (page furniture), SF-8/SF-10 (masthead/paragraph reflow).
**Fix direction:** Sort text runs by page → vertical → horizontal position before serialization; detect column boundaries and serialize full columns; treat fixed-pitch/indented regions as preformatted and wrap in fenced code with newlines preserved; insert spaces at glyph-run boundaries and de-hyphenate end-of-line hyphens; strip repeating header/footer bands; keep TOC as one entry per line.
**Priority:** **P1** (reading order SF-1 underlies many table/figure defects; preformatted SF-2 is critical for the RFC/arXiv class).

### G-10 — No full-text search / reliable navigation facility (P2, dependent on G-1/G-4/G-5)
**Theme:** Because page anchors (NAV-1), section offsets (NAV-2), and the heading index (NAV-3/4) are all unreliable, there is effectively **no dependable random-access/seek facility** into a large document. The indexed-read layer advertises navigability that does not exist.
**Fix direction:** After G-1/G-4/G-5 land, build the navigation index from corrected page boundaries + outline-seeded headings + post-normalization offsets, and validate anchors point at the claimed content (round-trip seek test in the fixture suite).
**Priority:** **P2** (blocked on the upstream P0/P1 fixes).

---

## 5. Prioritized fix backlog

1. **P0 — Fix PDF page-offset assignment** (G-1). Unblocks page citation/navigation across all large PDFs; precondition for figure placement. Affects 800-53r5, 800-63-3, 800-63b, CSF, BJS.
2. **P0 — Stop whitespace injection in code/JSON/URL spans; emit code verbatim** (G-2 / JC-1 / HX-4). Silent valid-but-wrong corruption. Affects schemastore.md, swagger.json, RFC 7230.
3. **P0 — Table column-boundary + merged/grouped-header reconstruction; no leading-char clipping; emit each table once** (G-3, variants T-1/T-2/T-3/T-4). Affects every complex PDF + Calibre DOCX.
4. **P0/P1 — Fix link-anchor hoisting that reverses normative meaning** (G-6 / HX-1 800-63b variant) and the XLSX merge-spill data fabrication (G-8 / P-3) and silent value corruption (P-2).
5. **P1 — HTML charset = UTF-8; compute all offsets against final content.md bytes** (G-4 / SF-6 / NAV-2). Affects Wikipedia GDP HTML; offset invariant benefits all formats.
6. **P1 — Constrain + outline-seed heading detection; recover dropped numbered sections; fix inverted hierarchy** (G-5 / NAV-3/4/6, SF-9). Affects all PDFs + HTML.
7. **P1 — Harvest `/URI`+`/Link` annotations into markdown links; preserve DOCX/XLSX link parity for PDF/PPTX; namespace footnotes** (G-6, HX-1/HX-2/HX-3/HX-4).
8. **P1 — Reading order + preformatted/monospace fidelity (ABNF/pseudo-code/ASCII-art/equations); strip page furniture; fix spacing/TOC reflow** (G-9 / SF-1/2/3/4/5/8/10, JC-2).
9. **P1 — XLSX formulas (`<f>` text), merge markers, header-detection heuristic, defined tables/charts** (G-8 / P-1/P-4/P-5/P-6/P-7).
10. **P1/P2 — Image alt-text/captions, stable paths, SmartArt/shape/OLE placeholders, reconcile `imageMarkers` metric** (G-7 / IMG-1..IMG-6, SF-12 PPTX title drop).
11. **P2 — Rebuild navigation/full-text-search facility on corrected anchors + add a round-trip seek validation test** (G-10).
12. **P2 — Metric/label cleanups:** `pipeTableRows` semantics (T-8), DOCX `pageCount` (P-9), printed-vs-physical page labels (P-8), CSV in-quote whitespace (SF-11).

---

*Report generated 2026-05-29 from a 125-finding adversarially-verified audit over a 21-file cross-format corpus.*
