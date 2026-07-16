package eu.euroswarms.surgeon.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.euroswarms.surgeon.data.AppConfig
import eu.euroswarms.surgeon.data.DraftPr
import eu.euroswarms.surgeon.data.DraftStatus
import eu.euroswarms.surgeon.data.LogLevel
import eu.euroswarms.surgeon.data.RepoTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Dashboard", "Review", "Setup")
    Scaffold(
        topBar = { TopAppBar(title = { Text("Surgeon · ${titles[tab]}") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("Dashboard") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.List, null) },
                    label = { Text("Review") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Setup") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> DashboardScreen(vm)
                1 -> ReviewScreen(vm)
                else -> SetupScreen(vm)
            }
        }
    }
}

@Composable
private fun DashboardScreen(vm: AppViewModel) {
    val config by vm.config.collectAsState()
    val isRunning by vm.isRunning.collectAsState()
    val lastMsg by vm.lastRunMessage.collectAsState()
    val logs by vm.logs.collectAsState()
    val drafts by vm.drafts.collectAsState()

    val today = drafts.count {
        it.status != DraftStatus.DISCARDED && it.status != DraftStatus.FAILED &&
            it.createdAt >= startOfTodayMillis()
    }
    val pending = drafts.count { it.status == DraftStatus.DRAFT }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Today", fontWeight = FontWeight.SemiBold)
                Text("$today drafted · target ${config.dailyMin}–${config.dailyMax}/day")
                Text("$pending awaiting your review")
                if (!config.isReady) {
                    Text(
                        "⚠ Setup incomplete — add GitHub token, Ollama endpoint, and repos.",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Auto-run: " + if (config.autoRunEnabled) "ON (background)" else "OFF",
                )
            }
        }

        Button(
            onClick = { vm.runNow() },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isRunning) {
                CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Working…")
            } else {
                Text("Draft one PR now")
            }
        }
        lastMsg?.let { Text(it) }

        Divider()
        Text("Activity log", fontWeight = FontWeight.SemiBold)
        if (logs.isEmpty()) {
            Text("No activity yet.", color = androidx.compose.material3.MaterialTheme.colorScheme.outline)
        } else {
            logs.take(40).forEach { entry ->
                val color = when (entry.level) {
                    LogLevel.ERROR -> androidx.compose.material3.MaterialTheme.colorScheme.error
                    LogLevel.WARN -> androidx.compose.material3.MaterialTheme.colorScheme.tertiary
                    LogLevel.SUCCESS -> androidx.compose.material3.MaterialTheme.colorScheme.primary
                    LogLevel.INFO -> androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                }
                Text("• ${entry.message}", color = color, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ReviewScreen(vm: AppViewModel) {
    val drafts by vm.drafts.collectAsState()
    val context = LocalContext.current
    val visible = drafts.filter { it.status == DraftStatus.DRAFT || it.status == DraftStatus.SUBMITTED }

    if (visible.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Nothing to review yet. Draft a PR from the Dashboard.")
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(visible, key = { it.id }) { draft ->
            DraftCard(
                draft = draft,
                onOpenPr = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(draft.compareUrl)))
                    }
                },
                onSubmitted = { vm.setDraftStatus(draft.id, DraftStatus.SUBMITTED) },
                onDiscard = { vm.setDraftStatus(draft.id, DraftStatus.DISCARDED) },
            )
        }
    }
}

@Composable
private fun DraftCard(
    draft: DraftPr,
    onOpenPr: () -> Unit,
    onSubmitted: () -> Unit,
    onDiscard: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${draft.repoFullName}#${draft.issueNumber}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (draft.isPrivateRepo) {
                    AssistChip(onClick = {}, label = { Text("PRIVATE") })
                }
            }
            Text(draft.issueTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "commit: ${draft.commitMessage.substringBefore('\n')}",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${draft.edits.size} file(s): ${draft.edits.joinToString { it.path.substringAfterLast('/') }}",
                fontSize = 12.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.outline,
            )
            if (draft.status == DraftStatus.SUBMITTED) {
                Text("✓ marked submitted", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
            }

            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide diff" else "Show diff")
            }
            if (expanded) {
                draft.edits.forEach { edit ->
                    Divider()
                    Text(edit.path, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (!edit.isNew && edit.oldString != null) {
                        Text("- ${edit.oldString}", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                    Text(
                        (if (edit.isNew) "new file:\n" else "+ ") + edit.newString,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    )
                }
                draft.agentNotes.takeIf { it.isNotBlank() }?.let {
                    Divider()
                    Text("agent: $it", fontSize = 12.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenPr, modifier = Modifier.weight(1f)) { Text("Open PR") }
                OutlinedButton(onClick = onSubmitted) { Text("Submitted") }
                OutlinedButton(onClick = onDiscard) { Text("Discard") }
            }
        }
    }
}

@Composable
private fun SetupScreen(vm: AppViewModel) {
    val saved by vm.config.collectAsState()

    var token by remember(saved) { mutableStateOf(saved.githubToken) }
    var ollamaUrl by remember(saved) { mutableStateOf(saved.ollamaBaseUrl) }
    var ollamaModel by remember(saved) { mutableStateOf(saved.ollamaModel) }
    var ollamaKey by remember(saved) { mutableStateOf(saved.ollamaApiKey) }
    var authorName by remember(saved) { mutableStateOf(saved.authorName) }
    var authorEmail by remember(saved) { mutableStateOf(saved.authorEmail) }
    var dailyMin by remember(saved) { mutableStateOf(saved.dailyMin.toString()) }
    var dailyMax by remember(saved) { mutableStateOf(saved.dailyMax.toString()) }
    var autoRun by remember(saved) { mutableStateOf(saved.autoRunEnabled) }
    val repos = remember(saved) { mutableStateListOf<RepoTarget>().apply { addAll(saved.repos) } }
    var savedFlash by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("GitHub", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(token, { token = it }, label = { Text("Personal access token (repo scope)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)

        Text("Ollama", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(ollamaUrl, { ollamaUrl = it }, label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(ollamaKey, { ollamaKey = it }, label = { Text("API key (Ollama Cloud, optional)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)

        // Model: free text plus a picker fed by the endpoint's /api/tags.
        val availableModels by vm.availableModels.collectAsState()
        val modelsMessage by vm.modelsMessage.collectAsState()
        var modelMenuOpen by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ollamaModel, { ollamaModel = it }, label = { Text("Model") },
                modifier = Modifier.weight(1f), singleLine = true)
            Box {
                TextButton(onClick = {
                    vm.fetchModels(ollamaUrl, ollamaKey)
                    modelMenuOpen = true
                }) { Text("List ▾") }
                DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                    if (availableModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(modelsMessage ?: "Loading models…") },
                            onClick = { },
                            enabled = false,
                        )
                    } else {
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    ollamaModel = model
                                    modelMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }
        modelsMessage?.let {
            Text(it, fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.outline)
        }

        Text("Commit identity", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(authorName, { authorName = it }, label = { Text("Author name") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(authorEmail, { authorEmail = it }, label = { Text("Author email") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)

        Text("Daily pacing", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(dailyMin, { dailyMin = it }, label = { Text("Min") },
                modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(dailyMax, { dailyMax = it }, label = { Text("Max") },
                modifier = Modifier.weight(1f), singleLine = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoRun, onCheckedChange = { autoRun = it })
            Spacer(Modifier.width(10.dp))
            Text("Run automatically in the background")
        }

        Text("Repositories", fontWeight = FontWeight.SemiBold)
        Text(
            "Each entry can pin the exact fork to use. Without a fork mapping, the app uses " +
                "<your login>/<repo> and creates the fork if it's missing.",
            fontSize = 12.sp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.outline,
        )

        var editIndex by remember { mutableStateOf<Int?>(null) } // null = closed, -1 = new entry
        repos.toList().forEachIndexed { index, repo ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(repo.fullName, fontSize = 14.sp)
                    repo.forkOverrideLabel?.let {
                        Text("fork: $it", fontSize = 12.sp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(onClick = { editIndex = index }) { Text("Edit") }
                TextButton(onClick = { repos.removeAt(index); savedFlash = false }) { Text("Remove") }
            }
        }
        OutlinedButton(onClick = { editIndex = -1 }, modifier = Modifier.fillMaxWidth()) {
            Text("Add repository")
        }

        editIndex?.let { idx ->
            RepoEditDialog(
                initial = repos.getOrNull(idx),
                onDismiss = { editIndex = null },
                onSave = { target ->
                    if (idx >= 0 && idx < repos.size) repos[idx] = target else repos.add(target)
                    savedFlash = false
                    editIndex = null
                },
            )
        }

        Button(
            onClick = {
                vm.saveConfig(
                    AppConfig(
                        githubToken = token.trim(),
                        ollamaBaseUrl = ollamaUrl.trim(),
                        ollamaModel = ollamaModel.trim(),
                        ollamaApiKey = ollamaKey.trim(),
                        authorName = authorName.trim(),
                        authorEmail = authorEmail.trim(),
                        dailyMin = dailyMin.toIntOrNull()?.coerceAtLeast(1) ?: 5,
                        dailyMax = dailyMax.toIntOrNull()?.coerceAtLeast(1) ?: 15,
                        autoRunEnabled = autoRun,
                        repos = repos.toList(),
                    ),
                )
                savedFlash = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save settings") }
        if (savedFlash) Text("Saved.", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Add/edit a repository entry: the upstream to watch and, optionally, the exact fork to use.
 * Blank fork fields mean "default": <your login> / <upstream repo name>.
 */
@Composable
private fun RepoEditDialog(
    initial: RepoTarget?,
    onDismiss: () -> Unit,
    onSave: (RepoTarget) -> Unit,
) {
    var owner by remember { mutableStateOf(initial?.owner.orEmpty()) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var forkOwner by remember { mutableStateOf(initial?.forkOwner.orEmpty()) }
    var forkName by remember { mutableStateOf(initial?.forkName.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add repository" else "Edit repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Upstream", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(owner, { owner = it }, label = { Text("Owner") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Repository") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Fork mapping (optional)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    "Leave blank to use <your login>/<repository> (auto-created if missing). " +
                        "Fill in to pin an existing fork — it is verified, never created.",
                    fontSize = 12.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                )
                OutlinedTextField(forkOwner, { forkOwner = it }, label = { Text("Fork owner") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(forkName, { forkName = it }, label = { Text("Fork repository") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = owner.isNotBlank() && name.isNotBlank(),
                onClick = {
                    onSave(
                        RepoTarget(
                            owner = owner.trim(),
                            name = name.trim(),
                            forkOwner = forkOwner.trim().ifBlank { null },
                            forkName = forkName.trim().ifBlank { null },
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun startOfTodayMillis(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
