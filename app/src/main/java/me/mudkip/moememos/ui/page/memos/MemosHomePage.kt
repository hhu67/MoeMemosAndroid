package me.mudkip.moememos.ui.page.memos

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import me.mudkip.moememos.ui.component.ActionIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation.NavHostController
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.mudkip.moememos.R
import me.mudkip.moememos.data.model.Account
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.ui.component.SyncStatusBadge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import me.mudkip.moememos.ui.page.common.LocalRootNavController
import me.mudkip.moememos.ui.page.common.RouteName
import me.mudkip.moememos.util.DynamicVoiceRecordButton
import me.mudkip.moememos.viewmodel.LocalMemos
import me.mudkip.moememos.viewmodel.LocalUserState
import me.mudkip.moememos.viewmodel.ManualSyncResult
import java.net.URLEncoder

@Composable
fun MemosHomePage(
    drawerState: DrawerState? = null,
    navController: NavHostController
) {
    MemoBrowser { onMemoClick ->
        MemosHomePageContent(drawerState, navController, onMemoClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemosHomePageContent(
    drawerState: DrawerState? = null,
    navController: NavHostController,
    onMemoClick: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rootNavController = LocalRootNavController.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsStateWithLifecycle()
    val syncStatus by memosViewModel.syncStatus.collectAsStateWithLifecycle()
    val quickRecorder = memosViewModel.quickVoiceRecorder
    val haptic = LocalHapticFeedback.current

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var isCancelActive by remember { mutableStateOf(false) }

    val expandedFab by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0
        }
    }
    var syncAlert by remember { mutableStateOf<HomeSyncAlert?>(null) }
    val context = LocalContext.current
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            rootNavController.navigate(RouteName.VOICE_RECORDING)
        } else {
            Toast.makeText(context, R.string.audio_permission_required.string, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun requestManualSync(allowHigherV1Version: String? = null) {
        when (val result = memosViewModel.refreshMemos(allowHigherV1Version)) {
            ManualSyncResult.Completed -> Unit
            is ManualSyncResult.Blocked -> {
                syncAlert = HomeSyncAlert.Blocked(result.message)
            }
            is ManualSyncResult.RequiresConfirmation -> {
                syncAlert = HomeSyncAlert.RequiresConfirmation(result.version, result.message)
            }
            is ManualSyncResult.Failed -> {
                syncAlert = HomeSyncAlert.Failed(result.message)
            }
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = R.string.memos.string) },
                navigationIcon = {
                    if (drawerState != null) {
                        ActionIconButton(label = R.string.menu.string, onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = R.string.menu.string)
                        }
                    }
                },
                actions = {
                    if (currentAccount !is Account.Local) {
                        SyncStatusBadge(
                            syncing = syncStatus.syncing,
                            unsyncedCount = syncStatus.unsyncedCount,
                            onSync = {
                                scope.launch {
                                    requestManualSync()
                                }
                            }
                        )
                    }
                    ActionIconButton(label = R.string.search.string, onClick = {
                        navController.navigate(RouteName.SEARCH)
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = R.string.search.string)
                    }
                }
            )
        },

        floatingActionButton = {
            val density = LocalDensity.current
            val cancelThresholdPx = with(density) { 110.dp.toPx() }
            val maxDragPx = with(density) { 220.dp.toPx() }
            val fabAlpha by animateFloatAsState(
                targetValue = if (quickRecorder.isRecording) 0f else 1f,
                animationSpec = tween(150),
                label = "fabAlpha"
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The actual voice memo button itself: expands and becomes a live dynamic Telegram blob
                DynamicVoiceRecordButton(
                    isRecording = quickRecorder.isRecording,
                    amplitude = quickRecorder.amplitude,
                    dragOffsetX = dragOffsetX,
                    isCancelActive = isCancelActive,
                    modifier = Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downTime = SystemClock.elapsedRealtime()
                            val startX = down.position.x
                            var isHold = false
                            val holdTimeout = 130L

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val pressed = event.changes.filter { it.pressed }
                                val now = SystemClock.elapsedRealtime()

                                if (!isHold) {
                                    val pointer = pressed.firstOrNull()
                                    val movedLeft = pointer != null && (pointer.position.x - startX) < -15f
                                    if (now - downTime >= holdTimeout || movedLeft) {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                            PackageManager.PERMISSION_GRANTED
                                        ) {
                                            isHold = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dragOffsetX = 0f
                                            isCancelActive = false
                                            quickRecorder.start()
                                        } else {
                                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            break
                                        }
                                    }
                                }

                                if (pressed.isEmpty()) {
                                    if (isHold) {
                                        if (isCancelActive) {
                                            quickRecorder.cancel()
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        } else {
                                            quickRecorder.stopAndSave()
                                            Toast.makeText(context, R.string.voice_memo_saved.string, Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        if (now - downTime < holdTimeout) {
                                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                                PackageManager.PERMISSION_GRANTED
                                            ) {
                                                rootNavController.navigate(RouteName.VOICE_RECORDING)
                                            } else {
                                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    }
                                    dragOffsetX = 0f
                                    isCancelActive = false
                                    break
                                }

                                val pointer = pressed.first()
                                if (isHold) {
                                    pointer.consume()
                                    val dx = pointer.position.x - startX
                                    dragOffsetX = dx.coerceIn(-maxDragPx, 0f)
                                    val shouldCancel = (-dragOffsetX) >= cancelThresholdPx
                                    if (shouldCancel != isCancelActive) {
                                        isCancelActive = shouldCancel
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        }
                    }
                )

                ExtendedFloatingActionButton(
                    onClick = {
                        if (!quickRecorder.isRecording) {
                            rootNavController.navigate(RouteName.INPUT)
                        }
                    },
                    expanded = expandedFab,
                    text = { Text(R.string.new_memo.string) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = R.string.compose.string) },
                    modifier = Modifier.alpha(fabAlpha)
                )
            }
        },

        content = { innerPadding ->
            MemosList(
                onMemoClick = onMemoClick,
                lazyListState = listState,
                contentPadding = innerPadding,
                additionalBottomPadding = MemoListFabAvoidancePadding,
                onRefresh = { requestManualSync() },
                onTagClick = { tag ->
                    navController.navigate("${RouteName.TAG}/${URLEncoder.encode(tag, "UTF-8")}") {
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    )

    when (val alert = syncAlert) {
        null -> Unit
        is HomeSyncAlert.Blocked -> {
            AlertDialog(
                onDismissRequest = { syncAlert = null },
                title = { Text(R.string.unsupported_memos_version_title.string) },
                text = { Text(alert.message) },
                confirmButton = {
                    TextButton(onClick = { syncAlert = null }) {
                        Text(R.string.close.string)
                    }
                }
            )
        }
        is HomeSyncAlert.RequiresConfirmation -> {
            AlertDialog(
                onDismissRequest = { syncAlert = null },
                title = { Text(R.string.unsupported_memos_version_title.string) },
                text = { Text(alert.message) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            syncAlert = null
                            scope.launch {
                                requestManualSync(allowHigherV1Version = alert.version)
                            }
                        }
                    ) {
                        Text(R.string.still_sync.string)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { syncAlert = null }) {
                        Text(R.string.cancel.string)
                    }
                }
            )
        }
        is HomeSyncAlert.Failed -> {
            AlertDialog(
                onDismissRequest = { syncAlert = null },
                title = { Text(R.string.sync_failed.string) },
                text = { Text(alert.message) },
                confirmButton = {
                    TextButton(onClick = { syncAlert = null }) {
                        Text(R.string.close.string)
                    }
                }
            )
        }
    }
}

private val MemoListFabAvoidancePadding = 96.dp
private val VoiceRecordRed = Color(0xFFD93025)

private sealed class HomeSyncAlert {
    data class Blocked(val message: String) : HomeSyncAlert()
    data class RequiresConfirmation(val version: String, val message: String) : HomeSyncAlert()
    data class Failed(val message: String) : HomeSyncAlert()
}
