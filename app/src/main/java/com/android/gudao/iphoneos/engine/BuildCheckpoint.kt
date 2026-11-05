package com.android.gudao.iphoneos.engine

import java.io.File

/**
 * Stage markers for the on-device image build.
 *
 * A build takes 20–40 minutes and used to be all-or-nothing: cancelling after the 7.4 GB extraction
 * meant extracting again on the next attempt, because nothing recorded that the DMGs were already
 * unpacked. Each marker is appended only after a stage returns successfully, so a retry skips exactly
 * the completed work. Cancelling never writes a marker, which is what makes "cancel then continue"
 * resume at the interrupted stage.
 */
class BuildCheckpoint(private val file: File) {

    fun stages(): Set<String> = runCatching {
        if (!file.isFile) emptySet()
        else file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    fun done(stage: String): Boolean = stage in stages()

    fun mark(stage: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (!done(stage)) file.appendText(stage + "\n")
        }
    }

    fun reset() {
        runCatching { file.delete() }
    }

    /**
     * Drop one stage's marker.
     *
     * A marker says a stage finished; it does not say what it produced is still there. The failure
     * card tells the user to delete `ios165/rootfs` when the extraction came out damaged, and the
     * fetch marker would then send the retry past the step that has to refill it -- see
     * ImageBuilder.build, which forgets a stage whose output is gone.
     */
    fun forget(stage: String) {
        runCatching {
            if (!file.isFile) return
            val kept = stages() - stage
            file.writeText(kept.joinToString("") { it + "\n" })
        }
    }
}
