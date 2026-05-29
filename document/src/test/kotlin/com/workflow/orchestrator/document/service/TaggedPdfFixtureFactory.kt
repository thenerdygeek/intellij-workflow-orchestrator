package com.workflow.orchestrator.document.service

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDNumberTreeNode
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Path

/**
 * Builds a **Tagged PDF** (StructTreeRoot + MarkInfo.Marked=true + per-MCID marked content)
 * with multiple text lines per page. This is the shape produced by Word/Acrobat PDFMaker —
 * the documents on which finding NAV-1 / G-1 reproduces.
 *
 * A tagged PDF causes Tika's PDFParser (with `extractMarkedContent=true`) to emit the entire
 * reading-order text as flat `<p>` blocks first and then dump empty `<div class="page">`
 * boundaries at the tail — collapsing all prose under page 1. With `extractMarkedContent=false`
 * Tika falls back to standard per-page extraction and the page divs interleave correctly.
 *
 * Each page K renders three distinctive lines beginning `"PageK marker alpha"`, etc., so a test
 * can assert that page K's text lands under page K's anchor.
 */
object TaggedPdfFixtureFactory {

    fun create(target: Path, pages: Int): Path {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

            // Mark the document as Tagged.
            doc.documentCatalog.markInfo = PDMarkInfo().apply { isMarked = true }
            val structRoot = PDStructureTreeRoot()
            doc.documentCatalog.structureTreeRoot = structRoot
            val docElem = PDStructureElement("Document", structRoot)
            structRoot.appendKid(docElem)

            val parentTreeNums = COSDictionary()

            for (i in 1..pages) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                // StructParents links page → parent-tree entry for this page's MCIDs.
                page.cosObject.setInt(COSName.STRUCT_PARENTS, i - 1)

                val pageMcidElems = COSArray()
                var mcid = 0

                PDPageContentStream(doc, page).use { cs ->
                    val lines = listOf(
                        "Page$i marker alpha — distinctive body text on page $i.",
                        "Page$i marker beta — second paragraph of page $i content.",
                        "Page$i marker gamma — third line so the page has real prose.",
                    )
                    var y = 720f
                    for (line in lines) {
                        // Wrap each line in a tagged paragraph marked-content region.
                        val elem = PDStructureElement("P", docElem)
                        elem.page = page
                        docElem.appendKid(elem)

                        val props = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                        cs.beginMarkedContent(COSName.P, org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(props))
                        cs.beginText()
                        cs.setFont(font, 12f)
                        cs.newLineAtOffset(72f, y)
                        cs.showText(line)
                        cs.endText()
                        cs.endMarkedContent()

                        // Record MCID → structure element in the page's parent-tree entry.
                        elem.cosObject.setItem(COSName.K, COSInteger.get(mcid.toLong()))
                        pageMcidElems.add(elem.cosObject)

                        mcid++
                        y -= 24f
                    }
                }
                parentTreeNums.setItem(COSName.getPDFName((i - 1).toString()), pageMcidElems)
            }

            val parentTree = PDNumberTreeNode(PDStructureElement::class.java)
            parentTree.cosObject.setItem(COSName.NUMS, parentTreeNumsToArray(parentTreeNums, pages))
            structRoot.parentTree = parentTree
            structRoot.parentTreeNextKey = pages

            doc.save(target.toFile())
        }
        return target
    }

    /** Flatten the per-page COSDictionary into the Nums array form [n0, kids0, n1, kids1, …]. */
    private fun parentTreeNumsToArray(perPage: COSDictionary, pages: Int): COSArray {
        val nums = COSArray()
        for (i in 0 until pages) {
            nums.add(COSInteger.get(i.toLong()))
            nums.add(perPage.getItem(COSName.getPDFName(i.toString())))
        }
        return nums
    }
}
