# `read_document` v1 fixture corpus

Each fixture's purpose, provenance and license is recorded here so the test suite can assert exact values without ambiguity, and so we can ship the corpus inside the plugin without licence anxiety.

## Synthetic fixtures (Apache 2.0, generated 2026-04-30 by `/tmp/gen_fixtures.py`)

| File | Format | Contents | Tests it anchors |
|---|---|---|---|
| `bug-tracker.xlsx` | XLSX | Sheet `Bugs` (5 rows: BUG-001..BUG-005, severities, dates, owners), sheet `Counts` with `COUNTIF` formulas, sheet `Notes` with merged cells `A2:A3` | XLSX cell-perfect extraction, formula evaluation, merged cells, multi-sheet ordering |
| `design-doc.docx` | DOCX | Two `Heading 1`s, two `Heading 2`s, two tables interleaved between paragraphs (RiskMatrix 3-row, AcceptanceCriteria 3-row), explicit prose-to-table reference | DOCX prose order around tables; `XWPFDocument.bodyElements` walk |
| `slides.pptx` | PPTX | Slide 1 (title + subtitle + speaker notes), Slide 2 (title + 4×3 table + notes), Slide 3 (title + body) | Slide title extraction, in-slide table, speaker notes |
| `spec-with-tables.pdf` | PDF | 2 pages: page 1 has §1 Intro prose + §2 ruled FR table (4 rows) + §3 ruled NFR table (3 rows); page 2 has §4 ruled Acceptance table (3 rows) | Lattice-mode Tabula extraction; prose-to-table interleaving via bbox merge; multi-page splice |
| `multi-page-table.pdf` | PDF | One 40-row table with header row repeated on page 2 (`repeatRows=1`) | Continuation-merge logic in `PdfTableExtractor` |
| `data.csv` | CSV | 3 rows × 3 cols (Name/Score/Grade) | Tika XHTML CSV → Table block |
| `report.html` | HTML | h1, h2, table with thead/tbody, paragraphs | Tika HTML parser → Heading/Paragraph/Table blocks |
| `release-notes.rtf` | RTF | Title + body paragraph | Tika RTF parser |
| `corrupt.pdf` | "PDF" | 44 bytes of garbage with `%PDF-1.4` header | Error path: malformed PDF → `ToolResult.Failure` |
| `zero.pdf` | empty | 0 bytes | Error path: zero-byte file |
| `wrong-extension.pdf` | text | Plain text with `.pdf` extension | Error path: MIME mismatch |

Reference values for accuracy assertions are documented at `:document/src/test/kotlin/com/workflow/orchestrator/document/fixtures/Expectations.kt`.

## Public-domain fixtures

| File | Source | License | Purpose |
|---|---|---|---|
| `nist-cybersecurity-framework.pdf` | [NIST CSWP 04162018](https://nvlpubs.nist.gov/nistpubs/CSWP/NIST.CSWP.04162018.pdf) | US Government Public Domain | Real-world spec PDF with structure, tables, footnotes; ~1MB; tests realistic document size and complexity |
| `ietf-rfc7230.pdf` | [IETF RFC 7230 PDF](https://www.rfc-editor.org/rfc/pdfrfc/rfc7230.txt.pdf) | IETF Trust / RFC license; PD for content | 89-page real spec, multi-column hint, ABNF, used to test `sortByPosition=true` and large-document truncation |

## MIT-licensed fixtures from Tabula's own corpus

| File | Source | License | Purpose |
|---|---|---|---|
| `tabula-eu-002.pdf` | [tabulapdf/tabula-java](https://github.com/tabulapdf/tabula-java/tree/master/src/test/resources/technology/tabula) | MIT | Real-world EU agency spreadsheet PDF; the file Tabula's own tests use to validate lattice-mode extraction |
| `tabula-multi-column.pdf` | [tabulapdf/tabula-java](https://github.com/tabulapdf/tabula-java/tree/master/src/test/resources/technology/tabula) | MIT | Multi-column layout test; used by Tabula to validate column ordering |
| `tabula-encrypted.pdf` | [tabulapdf/tabula-java](https://github.com/tabulapdf/tabula-java/tree/master/src/test/resources/technology/tabula) | MIT | Encrypted PDF; tests `EncryptedDocumentException` → `ToolResult.Failure` mapping |

## Provenance verification

To re-source the public-domain fixtures, see the URL column. To regenerate synthetic fixtures, run `/tmp/gen_fixtures.py` against an isolated Python venv with `openpyxl`, `python-docx`, `python-pptx`, `reportlab`. The script is checked in (location TBD when we land it during Phase 10).
