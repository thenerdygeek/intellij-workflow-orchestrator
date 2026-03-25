package com.workflow.orchestrator.core.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler

object GitMergeBaseUtil {

    private val log = Logger.getInstance(GitMergeBaseUtil::class.java)

    fun findMergeBase(project: Project, root: VirtualFile, branch1: String, branch2: String): String? {
        return try {
            val handler = GitLineHandler(project, root, GitCommand.MERGE_BASE)
            handler.addParameters(branch1, branch2)
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                parseMergeBaseOutput(result.outputAsJoinedString)
            } else {
                log.info("[Git:MergeBase] merge-base failed for '$branch1' and '$branch2': ${result.errorOutputAsJoinedString}")
                null
            }
        } catch (e: Exception) {
            log.info("[Git:MergeBase] Exception running merge-base: ${e.message}")
            null
        }
    }

    fun countDivergingCommits(project: Project, root: VirtualFile, from: String, mergeBase: String): Int {
        return try {
            val handler = GitLineHandler(project, root, GitCommand.REV_LIST)
            handler.addParameters("--count", "$mergeBase..$from")
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                parseRevListCount(result.outputAsJoinedString)
            } else {
                Int.MAX_VALUE
            }
        } catch (e: Exception) {
            Int.MAX_VALUE
        }
    }

    fun parseMergeBaseOutput(output: String): String? {
        val trimmed = output.trim()
        return trimmed.ifBlank { null }
    }

    fun parseRevListCount(output: String): Int {
        return output.trim().toIntOrNull() ?: Int.MAX_VALUE
    }
}
