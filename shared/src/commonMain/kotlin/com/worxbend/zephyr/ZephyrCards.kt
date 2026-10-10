package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.cleanupEligibility
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.JavaVersion
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.javaProviderName

/** Keep record identity readable before allowing actions to wrap underneath it. */
@Composable
internal fun ZephyrRecordLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    val fontScale = zephyrContentScale()
    BoxWithConstraints(modifier) {
        val actionWidth = maxWidth * 0.45f
        if (maxWidth < 760.dp * fontScale) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { actions() }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) { content() }
                FlowRow(
                    modifier = Modifier.widthIn(max = actionWidth),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { actions() }
            }
        }
    }
}

@Composable
internal fun CandidateGrid(
    candidates: List<Candidate>,
    protectedVersions: Set<ProtectedVersion> = emptySet(),
    reviewDueVersions: Set<ProtectedVersion> = emptySet(),
    cleanupVersionsByCandidate: Map<String, List<String>> = emptyMap(),
    onOpen: (Candidate) -> Unit,
    onClean: (String, List<String>) -> Unit,
) {
    val spacing = LocalZephyrMetrics.current.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(280.dp * zephyrContentScale()),
        verticalArrangement = Arrangement.spacedBy(spacing),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items(candidates, key = { it.name }) { candidate ->
            val eligibility = cleanupEligibility(candidate, protectedVersions, candidate.name in cleanupVersionsByCandidate)
            val protectedLocalOnly = candidate.localOnlyVersions.filter { version ->
                ProtectedVersion(candidate.name, version) in protectedVersions
            }
            val reviewDueCount = candidate.localOnlyVersions.count { version ->
                ProtectedVersion(candidate.name, version) in reviewDueVersions
            }
            CandidateCard(
                candidate = candidate,
                protectedLocalOnlyCount = protectedLocalOnly.size,
                reviewDueCount = reviewDueCount,
                cleanupEvidenceTrusted = candidate.name in cleanupVersionsByCandidate,
                cleanableVersions = eligibility.eligibleVersions.filter { it in cleanupVersionsByCandidate[candidate.name].orEmpty() },
                onClick = { onOpen(candidate) },
                onClean = {
                    onClean(candidate.name, eligibility.eligibleVersions.filter { it in cleanupVersionsByCandidate[candidate.name].orEmpty() })
                },
            )
        }
    }
}

@Composable
internal fun CandidateTable(
    candidates: List<Candidate>,
    protectedVersions: Set<ProtectedVersion> = emptySet(),
    cleanupVersionsByCandidate: Map<String, List<String>> = emptyMap(),
    onOpen: (Candidate) -> Unit,
    onClean: (String, List<String>) -> Unit,
) {
    val spacing = LocalZephyrMetrics.current.spacing
    val clipboard = LocalAppServices.current.clipboardService
    LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing)) {
        items(candidates, key = Candidate::name) { candidate ->
            val eligibility = cleanupEligibility(candidate, protectedVersions, candidate.name in cleanupVersionsByCandidate)
            val cleanable = eligibility.eligibleVersions.filter { it in cleanupVersionsByCandidate[candidate.name].orEmpty() }
            ContextActionArea(
                actions = buildList {
                    add(ContextAction("Inspect") { onOpen(candidate) })
                    add(ContextAction("Copy SDKMAN key") { clipboard.copy(candidate.name) })
                    if (cleanable.isNotEmpty()) {
                        add(ContextAction("Clean unprotected") { onClean(candidate.name, cleanable) })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ZephyrClickablePanel(onClick = { onOpen(candidate) }, modifier = Modifier.fillMaxWidth()) {
                    ZephyrRecordLayout(
                        modifier = Modifier.fillMaxWidth().padding(LocalZephyrMetrics.current.panelPadding),
                        content = {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CandidateIcon(candidate.kind)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(candidate.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(candidate.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "${candidate.installedVersions.count { it.isInstalled }} installed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        candidate.defaultVersion?.let { Badge("Default: $it", BadgeTone.Primary) }
                                        if (candidate.hasLocalOnlyVersions) {
                                            Badge("${candidate.localOnlyVersionCount} local-only", BadgeTone.Warning)
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        CopyTextButton(candidate.name, "Copy key")
                        if (cleanable.isNotEmpty()) {
                            OutlinedButton(onClick = { onClean(candidate.name, cleanable) }) {
                                Text("Clean")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PackageTable(
    packages: List<CandidateCatalogItem>,
    favoriteCandidates: Set<String>,
    onFavoriteChange: (String, Boolean) -> Unit,
    onOpen: (CandidateCatalogItem) -> Unit,
) {
    val spacing = LocalZephyrMetrics.current.spacing
    val clipboard = LocalAppServices.current.clipboardService
    LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing)) {
        items(packages, key = CandidateCatalogItem::name) { item ->
            val favorite = item.name in favoriteCandidates
            ContextActionArea(
                actions = listOf(
                    ContextAction("Inspect") { onOpen(item) },
                    ContextAction("Copy SDKMAN key") { clipboard.copy(item.name) },
                    ContextAction(if (favorite) "Remove favorite" else "Add favorite") {
                        onFavoriteChange(item.name, !favorite)
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ZephyrClickablePanel(onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth()) {
                    ZephyrRecordLayout(
                        modifier = Modifier.fillMaxWidth().padding(LocalZephyrMetrics.current.panelPadding),
                        content = {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CandidateIcon(item.kind)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(item.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        item.stableVersion?.let { Badge("Stable: $it", BadgeTone.Success) }
                                        if (item.isInstalled) Badge("Installed", BadgeTone.Primary)
                                        if (favorite) Badge("Favorite", BadgeTone.Primary)
                                    }
                                }
                            }
                        },
                    ) {
                        CopyTextButton(item.name, "Copy key")
                        TextButton(onClick = { onFavoriteChange(item.name, !favorite) }) {
                            Text(if (favorite) "★" else "☆")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CandidateCard(
    candidate: Candidate,
    protectedLocalOnlyCount: Int,
    reviewDueCount: Int = 0,
    cleanupEvidenceTrusted: Boolean = false,
    cleanableVersions: List<String> = emptyList(),
    onClick: () -> Unit,
    onClean: () -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val clipboard = LocalAppServices.current.clipboardService
    val cleanable = cleanupEvidenceTrusted && cleanableVersions.isNotEmpty()
    ContextActionArea(
        actions = buildList {
            add(ContextAction("Inspect") { onClick() })
            add(ContextAction("Copy SDKMAN key") { clipboard.copy(candidate.name) })
            if (cleanable) add(ContextAction("Clean unprotected") { onClean() })
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZephyrClickablePanel(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (metrics.controlHeight <= 32.dp) 174.dp else 194.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(metrics.panelPadding), verticalArrangement = Arrangement.spacedBy(metrics.spacing)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CandidateIcon(candidate.kind)
                    Column(Modifier.weight(1f)) {
                        Text(candidate.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("SDKMAN key: ${candidate.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LinkText(
                    text = candidate.description ?: "${candidate.installedVersions.count { it.isInstalled }} installed version(s)",
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 2,
                )
                Spacer(Modifier.weight(1f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    candidate.defaultVersion?.let { Badge("Default: $it", BadgeTone.Primary) }
                    if (candidate.hasLocalOnlyVersions) Badge("${candidate.localOnlyVersionCount} local-only", BadgeTone.Warning)
                    if (reviewDueCount > 0) Badge("$reviewDueCount review due", BadgeTone.Error)
                    if (protectedLocalOnlyCount > 0) Badge("$protectedLocalOnlyCount protected", BadgeTone.Primary)
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CopyTextButton(candidate.name, "Copy key")
                    if (candidate.hasLocalOnlyVersions) {
                        if (cleanable) {
                            OutlinedButton(onClick = onClean, modifier = Modifier.heightIn(min = metrics.controlHeight)) {
                                Text("Clean unprotected", style = MaterialTheme.typography.labelLarge)
                            }
                        } else if (cleanupEvidenceTrusted) {
                            Text(
                                "All local-only versions are default or protected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "Cleanup requires a completed trusted read",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PackageCard(
    item: CandidateCatalogItem,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val clipboard = LocalAppServices.current.clipboardService
    ContextActionArea(
        actions = buildList {
            add(ContextAction("Inspect") { onClick() })
            add(ContextAction("Copy SDKMAN key") { clipboard.copy(item.name) })
            onToggleFavorite?.let {
                add(ContextAction(if (isFavorite) "Remove favorite" else "Add favorite") { it() })
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZephyrClickablePanel(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (metrics.controlHeight <= 32.dp) 164.dp else 184.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(metrics.panelPadding), verticalArrangement = Arrangement.spacedBy(metrics.spacing)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CandidateIcon(item.kind)
                    Column(Modifier.weight(1f)) {
                        Text(item.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("SDKMAN key: ${item.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LinkText(item.description ?: "Available from SDKMAN.", Modifier.weight(1f, fill = false), maxLines = 2)
                Spacer(Modifier.weight(1f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.stableVersion?.let { Badge("Stable: $it", BadgeTone.Success) }
                    if (item.isInstalled) Badge("Installed", BadgeTone.Primary)
                    if (isFavorite) Badge("Favorite", BadgeTone.Primary)
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CopyTextButton(item.name, "Copy key")
                    if (onToggleFavorite != null) {
                        TextButton(onClick = onToggleFavorite, modifier = Modifier.heightIn(min = metrics.controlHeight)) {
                            Text(if (isFavorite) "★ Favorited" else "☆ Favorite")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun JdkVersionCard(
    version: JavaVersion,
    default: String?,
    isProtected: Boolean,
    onToggleProtected: () -> Unit,
    onClean: () -> Unit,
    onOpenTerminal: (() -> Unit)? = null,
    cleanupEligible: Boolean = false,
) {
    val metrics = LocalZephyrMetrics.current
    val clipboard = LocalAppServices.current.clipboardService
    ContextActionArea(
        actions = buildList {
            add(ContextAction("Copy version") { clipboard.copy(version.identifier) })
            onOpenTerminal?.let {
                add(ContextAction("Open activated terminal") { it() })
            }
            add(ContextAction(if (isProtected) "Unpin" else "Protect") { onToggleProtected() })
            if (cleanupEligible) {
                add(ContextAction("Clean") { onClean() })
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZephyrPanel {
            ZephyrRecordLayout(
                modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                content = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        CandidateIcon(CandidateKind.Jdk)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("JDK ${version.featureVersion}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(version.identifier, style = MaterialTheme.typography.bodyMedium)
                            Text(version.providerName ?: javaProviderName(version.providerCode) ?: "Provider unknown", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Badge("SDKMAN key: java")
                                if (version.identifier == default) Badge("Default", BadgeTone.Primary)
                                when {
                                    version.isConfirmedLocalOnly -> Badge("Local only", BadgeTone.Warning)
                                    !version.isRemoteAvailable -> Badge("Availability unknown", BadgeTone.Neutral)
                                }
                                if (isProtected) Badge("Protected", BadgeTone.Primary)
                            }
                        }
                    }
                },
            ) {
                CopyTextButton(version.identifier, "Copy version")
                if (onOpenTerminal != null) {
                    TextButton(onClick = onOpenTerminal) { Text("Terminal") }
                }
                TextButton(onClick = onToggleProtected) { Text(if (isProtected) "Unpin" else "Protect") }
                if (cleanupEligible) {
                    OutlinedButton(onClick = onClean) { Text("Clean") }
                }
                if (version.isConfirmedLocalOnly && version.identifier == default) {
                    Text("Choose another default first", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
