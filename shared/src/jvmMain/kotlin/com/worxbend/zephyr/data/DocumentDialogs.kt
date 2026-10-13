package com.worxbend.zephyr.data

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** Dialog seam for hermetic document workflow tests; filesystem IO stays off the EDT. */
internal interface DocumentDialogs {
    fun chooseOpen(
        title: String,
        extension: String,
    ): Path?

    fun chooseSave(
        title: String,
        suggestedName: String,
        extension: String,
    ): Path?

    fun confirmOverwrite(path: Path): Boolean
}

internal object SwingDocumentDialogs : DocumentDialogs {
    override fun chooseOpen(
        title: String,
        extension: String,
    ): Path? =
        onDocumentEdt {
            val chooser = chooser(title, extension)
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
        }

    override fun chooseSave(
        title: String,
        suggestedName: String,
        extension: String,
    ): Path? =
        onDocumentEdt {
            val chooser = chooser(title, extension).apply { selectedFile = java.io.File(suggestedName) }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
        }

    override fun confirmOverwrite(path: Path): Boolean =
        onDocumentEdt {
            JOptionPane.showConfirmDialog(
                null,
                "${path.fileName} already exists. Replace it?",
                "Confirm overwrite",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            ) == JOptionPane.YES_OPTION
        }

    private fun chooser(
        title: String,
        extension: String,
    ) = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileNameExtensionFilter("Documents (.$extension)", extension)
    }
}

private fun <T> onDocumentEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    val result = AtomicReference<T>()
    SwingUtilities.invokeAndWait { result.set(block()) }
    return result.get()
}
