package com.example.smartradio.ui

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.example.smartradio.R
import com.example.smartradio.data.DiscoveredStation
import com.example.smartradio.data.Station
import com.example.smartradio.data.StationKind
import com.example.smartradio.ui.theme.AvatarPalette
import com.example.smartradio.ui.theme.MutedAmber
import com.example.smartradio.ui.theme.PillBackground
import com.example.smartradio.ui.theme.PillText
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun RadioScreen(viewModel: RadioViewModel) {
    val stations by viewModel.stations.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentStationId by viewModel.currentStationId.collectAsState()
    val playbackError by viewModel.playbackError.collectAsState()
    val nowPlayingTrack by viewModel.nowPlayingTrack.collectAsState()
    val noStationsAvailable by viewModel.noStationsAvailable.collectAsState()
    val autoSkipEvent by viewModel.autoSkipEvent.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var localOrder by remember(stations) { mutableStateOf(stations) }
    val currentStation = stations.find { it.id == currentStationId }

    var toastEvent by remember { mutableStateOf<AutoSkipEvent?>(null) }
    LaunchedEffect(autoSkipEvent) {
        val event = autoSkipEvent ?: return@LaunchedEffect
        toastEvent = event
        delay(3500)
        if (toastEvent?.id == event.id) toastEvent = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Image(
                        painter = painterResource(R.drawable.skipadoodle_wordmark),
                        contentDescription = "Skipadoodle",
                        modifier = Modifier.height(34.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add station", tint = Color.White)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            AnimatedVisibility(
                visible = toastEvent != null,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                toastEvent?.let { AutoSkipToast(it) }
            }

            NowPlayingHeader(
                station = currentStation,
                isPlaying = isPlaying,
                noStationsAvailable = noStationsAvailable,
                playbackError = playbackError,
                nowPlayingTrack = nowPlayingTrack,
                onTogglePlay = { viewModel.togglePlayPause() }
            )

            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(
                    text = "Set preference order with the arrows below.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    text = "Auto-skips ads/talk.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            if (localOrder.isEmpty()) {
                EmptyStationsPlaceholder(
                    onAddStation = { showAddDialog = true },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(localOrder, key = { _, station -> station.id }) { index, station ->
                        val isSelected = station.id == currentStationId
                        StationCard(
                            station = station,
                            avatarColor = AvatarPalette[index % AvatarPalette.size],
                            isSelected = isSelected,
                            isPlaying = isSelected && isPlaying,
                            nowPlayingTrack = if (isSelected) nowPlayingTrack else null,
                            canMoveUp = index > 0,
                            canMoveDown = index < localOrder.size - 1,
                            onClick = { viewModel.selectStation(station.id) },
                            onRemove = { viewModel.removeStation(station.id) },
                            onMoveUp = {
                                localOrder = localOrder.toMutableList().apply {
                                    add(index - 1, removeAt(index))
                                }
                                viewModel.reorder(localOrder.map { it.id })
                            },
                            onMoveDown = {
                                localOrder = localOrder.toMutableList().apply {
                                    add(index + 1, removeAt(index))
                                }
                                viewModel.reorder(localOrder.map { it.id })
                            }
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
private fun EmptyStationsPlaceholder(onAddStation: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Radio,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No stations yet",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Search the directory or add a stream URL manually to start listening.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAddStation) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add a station")
        }
    }
}

@Composable
private fun AutoSkipToast(event: AutoSkipEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PillBackground)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                "Skipped ${event.fromStationName} — talk detected",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            if (event.toStationName.isNotBlank()) {
                Text(
                    "Switched to ${event.toStationName}",
                    color = Color(0xFFA9AEC2),
                    fontSize = 9.5.sp
                )
            }
        }
    }
}

@Composable
private fun NowPlayingHeader(
    station: Station?,
    isPlaying: Boolean,
    noStationsAvailable: Boolean,
    playbackError: String?,
    nowPlayingTrack: String?,
    onTogglePlay: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(20.dp, 12.dp, 20.dp, 8.dp)) {
        if (station != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "NOW PLAYING",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        station.name,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (!nowPlayingTrack.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "♪ $nowPlayingTrack",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Pill(station.kind.pillLabel())
                }
                Waveform(isPlaying = isPlaying, modifier = Modifier.height(28.dp).width(84.dp))
            }
            if (noStationsAvailable) {
                Spacer(Modifier.height(8.dp))
                NoStationsBanner()
            }
            if (playbackError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Couldn't play this station: $playbackError",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
        } else {
            Text(
                "Tap a station below to start listening.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun NoStationsBanner() {
    val infiniteTransition = rememberInfiniteTransition(label = "no-stations-pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MutedAmber.copy(alpha = 0.14f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Radio,
            contentDescription = null,
            tint = MutedAmber,
            modifier = Modifier.size(16.dp).alpha(alpha)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text("NO STATIONS AVAILABLE", color = MutedAmber, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            Text(
                "Tried every station three times over — talk, ads, or unreachable. Tap a station below to try again.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.5.sp
            )
        }
    }
}

@Composable
private fun Waveform(isPlaying: Boolean, modifier: Modifier = Modifier, barCount: Int = 18) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing)),
        label = "phase"
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        repeat(barCount) { i ->
            val fraction = if (isPlaying) {
                val raw = (kotlin.math.sin(phase + i * 0.6f) + 1f) / 2f
                0.2f + raw * 0.8f
            } else {
                0.18f
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .background(
                        color = if (isPlaying) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        },
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

@Composable
private fun Pill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(PillBackground)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text, color = PillText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StationCard(
    station: Station,
    avatarColor: Color,
    isSelected: Boolean,
    isPlaying: Boolean,
    nowPlayingTrack: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isSelected) 0.dp else 1.dp,
        shadowElevation = if (isSelected) 2.dp else 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (isSelected) 9.dp else 12.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(52.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent)
                )
                Spacer(Modifier.width(9.dp))
            }

            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                if (station.favicon.isNotBlank()) {
                    AsyncImage(
                        model = station.favicon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = station.kind.icon(),
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        station.name,
                        fontSize = 13.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Pill(station.kind.pillLabel(short = true))
                }
                if (isSelected) {
                    Text(
                        if (isPlaying) "▶ PLAYING" else "PAUSED",
                        color = accent,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                val metaParts = buildList {
                    val quality = station.qualitySummary()
                    if (quality.isNotBlank()) add(quality)
                    station.primaryLanguage()?.let { add("($it)") }
                    val location = station.locationSummary()
                    if (location.isNotBlank()) add(location)
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        metaParts.joinToString(" · "),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (!nowPlayingTrack.isNullOrBlank()) {
                    Text(
                        "♪ $nowPlayingTrack",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            Column(horizontalAlignment = Alignment.End) {
                if (station.clickCount > 0) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = "Popular station",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircleActionButton(icon = Icons.Default.KeyboardArrowUp, enabled = canMoveUp, filled = true, onClick = onMoveUp)
                        Spacer(Modifier.height(5.dp))
                        CircleActionButton(icon = Icons.Default.KeyboardArrowDown, enabled = canMoveDown, filled = true, onClick = onMoveDown)
                    }
                    Spacer(Modifier.width(6.dp))
                    CircleActionButton(icon = Icons.Default.Close, enabled = true, filled = false, onClick = onRemove)
                }
            }
        }
    }
}

@Composable
private fun CircleActionButton(
    icon: ImageVector,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit
) {
    val background = when {
        filled && enabled -> MaterialTheme.colorScheme.primary
        filled && !enabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val tint = if (filled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun AddStationDialog(
    viewModel: RadioViewModel,
    onDismiss: () -> Unit,
    onAdded: () -> Unit
) {
    val context = LocalContext.current
    var manualMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsState()
    val loading by viewModel.searchLoading.collectAsState()
    val error by viewModel.searchError.collectAsState()
    val popular by viewModel.popularStations.collectAsState()
    val popularLoading by viewModel.popularLoading.collectAsState()
    val nearYouCountry by viewModel.nearYouCountryName.collectAsState()
    val nearYouLoading by viewModel.nearYouLoading.collectAsState()
    val locationDenied by viewModel.locationPermanentlyDenied.collectAsState()
    val pendingRisky by viewModel.pendingRiskyStation.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadPopularStations() }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onLocationPermissionGranted()
        } else {
            val activity = context as? Activity
            val permanentlyDenied = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.ACCESS_COARSE_LOCATION
            )
            viewModel.onLocationPermissionDenied(permanentlyDenied)
        }
    }

    fun addResult(result: DiscoveredStation) {
        viewModel.addDiscoveredStation(result)
        if (viewModel.pendingRiskyStation.value == null) onAdded()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (manualMode) "Add station manually" else "Find a station") },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp)) {
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

                    if (query.isBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (nearYouCountry != null) "Popular near you" else "Popular stations",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            NearYouChip(
                                resolvedCountry = nearYouCountry,
                                loading = nearYouLoading,
                                disabled = locationDenied,
                                onClick = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        when {
                            popularLoading -> Box(
                                Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator() }
                            popular.isEmpty() -> Text(
                                "Couldn't load popular stations right now.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            else -> LazyColumn(
                                modifier = Modifier.heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(popular, key = { it.streamUrl }) { result ->
                                    StationResultRow(station = result, onClick = { addResult(result) })
                                }
                            }
                        }
                    } else {
                        when {
                            loading -> Box(
                                Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator() }
                            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                            results.isEmpty() -> Text("No matches — try a different spelling, or add it manually.")
                            else -> LazyColumn(
                                modifier = Modifier.heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(results, key = { it.streamUrl }) { result ->
                                    StationResultRow(station = result, onClick = { addResult(result) })
                                }
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

    if (pendingRisky != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingStation() },
            title = { Text("Stream may not be working") },
            text = { Text("${pendingRisky!!.name} failed its last connectivity check. Add it anyway?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmAddPendingStation()
                    onAdded()
                }) { Text("Add anyway") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPendingStation() }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NearYouChip(
    resolvedCountry: String?,
    loading: Boolean,
    disabled: Boolean,
    onClick: () -> Unit
) {
    val active = resolvedCountry != null
    val background = when {
        active -> MaterialTheme.colorScheme.primary
        disabled -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val contentColor = when {
        active -> Color.White
        disabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .then(
                if (!active && !disabled) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                } else Modifier
            )
            .then(if (!disabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = contentColor)
        } else {
            Icon(Icons.Default.LocationOn, contentDescription = null, tint = contentColor, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(resolvedCountry ?: "Near you", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = contentColor)
    }
}

@Composable
private fun StationResultRow(station: DiscoveredStation, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                if (station.favicon.isNotBlank()) {
                    AsyncImage(
                        model = station.favicon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        station.guessedKind().icon(),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        station.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Pill(station.guessedKind().pillLabel(short = true))
                }
                val metaParts = buildList {
                    val quality = station.qualitySummary()
                    if (quality.isNotBlank()) add(quality)
                    station.primaryLanguage()?.let { add("($it)") }
                    val location = station.locationSummary()
                    if (location.isNotBlank()) add(location)
                }
                if (metaParts.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        metaParts.joinToString(" · "),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            if (station.clickCount > 0) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                    contentDescription = "Popular station",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun StationKind.pillLabel(short: Boolean = false): String = when (this) {
    StationKind.FM_SIMULCAST -> if (short) "FM" else "FM simulcast"
    StationKind.AM_SIMULCAST -> if (short) "AM" else "AM simulcast"
    StationKind.DIGITAL -> "Digital"
}

private fun StationKind.icon(): ImageVector = when (this) {
    StationKind.FM_SIMULCAST, StationKind.AM_SIMULCAST -> Icons.Default.Radio
    StationKind.DIGITAL -> Icons.Default.MusicNote
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
                selected = kind == StationKind.AM_SIMULCAST,
                onClick = { kind = StationKind.AM_SIMULCAST },
                label = { Text("AM simulcast") }
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
