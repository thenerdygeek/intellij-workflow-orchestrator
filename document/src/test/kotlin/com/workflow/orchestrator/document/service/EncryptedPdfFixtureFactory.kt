package com.workflow.orchestrator.document.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.nio.file.Path

object EncryptedPdfFixtureFactory {
    fun create(target: Path): Path {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val policy = StandardProtectionPolicy("owner-pw", "user-pw", AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(target.toFile())
        }
        return target
    }
}
