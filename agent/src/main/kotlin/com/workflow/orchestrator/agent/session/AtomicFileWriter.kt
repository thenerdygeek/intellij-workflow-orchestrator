package com.workflow.orchestrator.agent.session

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AtomicFileWriter {

    fun write(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parent, "${target.name}.tmp.${System.currentTimeMillis()}.${(Math.random() * 100000).toInt()}")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}
