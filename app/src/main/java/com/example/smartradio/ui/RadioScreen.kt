package com.example.smartradio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.example.smartradio.data.Station
import com.example.smartradio.data.StationKind
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun RadioScreen(viewModel: RadioViewModel) {
    val stations by viewModel.stations.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var localOrder by remember(stations) { mutableStateOf(stations) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Smart Radio") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add station")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Drag to set preference order. Auto-skips ads/talk.",
                    style = MaterialTheme.typography.bodyMedium
                )
                IconButton(onClick = { viewModel.togglePlayPause() }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause"
                    )
                }
            }

            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
                localOrder = localOrder.toMutableList().apply {
                    add(to.index, removeAt(from.index))
                }
                viewModel.reorder(localOrder.map { it.id })
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(localOrder, key = { it.id }) { station ->
                    ReorderableItem(reorderableState, key = station.id) { _ ->
                        StationRow(
                            station = station,
                            onClick = { viewModel.selectStation(station.id) },
                            onRemove = { viewModel.removeStation(station.id) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddStationDialog(
            viewModel = viewModel,
            onDismiss = {
                viewModel.clearSearch()
                showAddDialog = false
            },
            onAdded = {
                viewModel.clearSearch()
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun StationRow(station: Station, onClick: () -> Unit, onRemove: () -> Unit) {
    ListItem(
        headlineContent = { Text(station.name) },
        supportingContent = {
            Text(if (station.kind == StationKind.FM_SIMULCAST) "FM simulcast" else "Digital")
        },
        leadingContent = { Icon(Icons.Default.DragHandle, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove")
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    )
    Divider()
}

@Composable
private fun AddStationDialog(
    viewModel: RadioViewModel,
    onDismiss: () -> Unit,
    onAdded: () -> Unit
) {
    var manualMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsState()
    val loading by viewModel.searchLoading.collectAsState()
    val error by viewModel.searchError.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (manualMode) "Add station manually" else "Find a station") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                if (!manualMode) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            viewModel.searchStations(it)
                        },
                        label = { Text("Station name, e.g. \"98.7 The Bay\"") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    when {
                        loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        query.isNotBlank() && results.isEmpty() -> Text("No matches — try a different spelling, or add it manually.")
                        else -> LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(results, key = { it.streamUrl }) { result ->
                                ListItem(
                                    headlineContent = { Text(result.name) },
                                    supportingContent = { Text(if (result.countryCode.isNotBlank()) result.countryCode else result.tags) },
                                    modifier = Modifier.clickable {
                                        viewModel.addDiscoveredStation(result)
                                        onAdded()
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { manualMode = true }) { Text("Can't find it — add manually") }
                } else {
                    ManualAddFields(
                        onAdd = { name, url, kind ->
                            viewModel.addStation(name, url, kind)
                            onAdded()
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { manualMode = false }) { Text("← Back to search") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ManualAddFields(onAdd: (name: String, url: String, kind: StationKind) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(StationKind.FM_SIMULCAST) }

    Column {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Stream URL") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row {
            FilterChip(
                selected = kind == StationKind.FM_SIMULCAST,
                onClick = { kind = StationKind.FM_SIMULCAST },
                label = { Text("FM simulcast") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = kind == StationKind.DIGITAL,
                onClick = { kind = StationKind.DIGITAL },
                label = { Text("Digital") }
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (name.isNotBlank() && url.isNotBlank()) onAdd(name, url, kind) },
            modifier = Modifier.align(Alignment.End)
        ) { Text("Add") }
    }
}
