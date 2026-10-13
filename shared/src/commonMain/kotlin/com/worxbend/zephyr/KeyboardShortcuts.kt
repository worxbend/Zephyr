package com.worxbend.zephyr

import com.worxbend.zephyr.viewmodel.ZephyrRoute

internal enum class WorkspaceShortcutKey {
    O,
    J,
    S,
    U,
    D,
    H,
}

internal data class KeyboardShortcutInfo(
    val keys: String,
    val description: String,
)

internal val keyboardShortcutHelp =
    listOf(
        KeyboardShortcutInfo("Ctrl/⌘ K", "Open global search"),
        KeyboardShortcutInfo("Ctrl/⌘ Shift P", "Open command palette"),
        KeyboardShortcutInfo("Ctrl/⌘ Shift O", "Open Overview"),
        KeyboardShortcutInfo("Ctrl/⌘ Shift J", "Open Installed JDK"),
        KeyboardShortcutInfo("Ctrl/⌘ Shift S", "Open Installed SDKs"),
        KeyboardShortcutInfo("Ctrl/⌘ Shift U", "Open Update Center"),
        KeyboardShortcutInfo("Ctrl/⌘ Shift D", "Open Diagnostics"),
        KeyboardShortcutInfo("Ctrl/⌘ Shift H", "Open Task Center"),
        KeyboardShortcutInfo("Ctrl/⌘ Shift R", "Refresh local state"),
        KeyboardShortcutInfo("Ctrl/⌘ Shift L", "Scan local-only versions"),
        KeyboardShortcutInfo("Tab / Shift+Tab", "Move focus forward / backward"),
        KeyboardShortcutInfo("Enter / Space", "Activate the focused control"),
    )

internal fun resolveWorkspaceShortcut(
    key: WorkspaceShortcutKey,
    primaryPressed: Boolean,
    shiftPressed: Boolean,
): ZephyrRoute? {
    if (!primaryPressed || !shiftPressed) return null
    return when (key) {
        WorkspaceShortcutKey.O -> ZephyrRoute.Overview
        WorkspaceShortcutKey.J -> ZephyrRoute.InstalledJdk
        WorkspaceShortcutKey.S -> ZephyrRoute.InstalledSdks
        WorkspaceShortcutKey.U -> ZephyrRoute.UpdateCenter
        WorkspaceShortcutKey.D -> ZephyrRoute.Diagnostics
        WorkspaceShortcutKey.H -> ZephyrRoute.History
    }
}
