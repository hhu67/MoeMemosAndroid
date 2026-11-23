package me.mudkip.moememos.ui.page.memos

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderDelete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import me.mudkip.moememos.R
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.ui.component.ActionIconButton
import me.mudkip.moememos.ui.component.CreateFolderDialog
import me.mudkip.moememos.ui.page.common.LocalRootNavController
import me.mudkip.moememos.ui.page.common.RouteName
import me.mudkip.moememos.util.DynamicVoiceRecordButton
import me.mudkip.moememos.viewmodel.LocalMemos
import java.net.URLEncoder

@Composable
fun FolderPage(
    folderName: String,
    navController: NavHostController,
    drawerState: DrawerState? = null,
) {
    MemoBrowser { onMemoClick ->
        FolderPageContent(folderName, navController, onMemoClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPageContent(
    folderName: String,
    navController: NavHostController,
    onMemoClick: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rootNavController = LocalRootNavController.current
    val memosViewModel = LocalMemos.current
    val currentFolder = memosViewModel.folders.find { it.name == folderName }
    val folderTitle = currentFolder?.title ?: folderName

    val quickRecorder = memosViewModel.quickVoiceRecorder
    val haptic = LocalHapticFeedback.current

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var isCancelActive by remember { mutableStateOf(false) }
    var showCreateSubfolderDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteMenu by remember { mutableStateOf(false) }
    var showDeleteOnlyDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    val expandedFab by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0
        }
    }
    val context = LocalContext.current
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            rootNavController.navigate("${RouteName.VOICE_RECORDING}?folder=${Uri.encode(folderName)}")
        } else {
            Toast.makeText(context, R.string.audio_permission_required.string, Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(folderName) {
        onDispose {
            if (!quickRecorder.isRecording) {
                quickRecorder.targetFolder = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = folderTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    ActionIconButton(
                        label = stringResource(R.string.back),
                        onClick = { navController.popBackStack() }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    ActionIconButton(
                        label = stringResource(R.string.new_subfolder),
                        onClick = { showCreateSubfolderDialog = true }
                    ) {
                        Icon(
                            Icons.Outlined.CreateNewFolder,
                            contentDescription = stringResource(R.string.new_subfolder)
                        )
                    }
                    ActionIconButton(
                        label = stringResource(R.string.rename),
                        onClick = { showRenameDialog = true }
                    ) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.rename)
                        )
                    }
                    Box {
                        ActionIconButton(
                            label = stringResource(R.string.delete_folder),
                            onClick = { showDeleteMenu = true }
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.delete_folder)
                            )
                        }

                        DropdownMenu(
                            expanded = showDeleteMenu,
                            onDismissRequest = { showDeleteMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_folder_only)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.FolderDelete,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showDeleteMenu = false
                                    showDeleteOnlyDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.delete_folder_and_contents),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteForever,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error
                                ),
                                onClick = {
                                    showDeleteMenu = false
                                    showDeleteAllDialog = true
                                }
                            )
                        }
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
                DynamicVoiceRecordButton(
                    isRecording = quickRecorder.isRecording,
                    amplitude = quickRecorder.amplitude,
                    dragOffsetX = dragOffsetX,
                    isCancelActive = isCancelActive,
                    modifier = Modifier.pointerInput(folderName) {
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
                                            quickRecorder.targetFolder = folderName
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
                                                rootNavController.navigate("${RouteName.VOICE_RECORDING}?folder=${Uri.encode(folderName)}")
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
                            rootNavController.navigate("${RouteName.INPUT}?folder=${Uri.encode(folderName)}")
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
                folderName = folderName,
                onMemoClick = onMemoClick,
                lazyListState = listState,
                contentPadding = innerPadding,
                additionalBottomPadding = MemoListFabAvoidancePadding,
                onRefresh = { memosViewModel.refreshMemos() },
                onFolderClick = { subfolder ->
                    navController.navigate("${RouteName.FOLDER}/${Uri.encode(subfolder.name)}")
                },
                onTagClick = { tag ->
                    navController.navigate("${RouteName.TAG}/${URLEncoder.encode(tag, "UTF-8")}") {
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    )

    if (showCreateSubfolderDialog) {
        CreateFolderDialog(
            parentFolderName = folderName,
            onDismiss = { showCreateSubfolderDialog = false },
            onConfirm = { subfolderTitle ->
                showCreateSubfolderDialog = false
                scope.launch {
                    memosViewModel.createFolder(subfolderTitle, parent = folderName)
                }
            }
        )
    }

    if (showRenameDialog) {
        var newTitle by remember { mutableStateOf(folderTitle) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename_folder)) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newTitle.trim()
                        if (trimmed.isNotBlank()) {
                            showRenameDialog = false
                            scope.launch {
                                memosViewModel.updateFolder(folderName, trimmed)
                            }
                        }
                    },
                    enabled = newTitle.trim().isNotBlank()
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDeleteOnlyDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteOnlyDialog = false },
            title = { Text(stringResource(R.string.delete_folder)) },
            text = { Text(stringResource(R.string.delete_folder_confirm, folderTitle)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteOnlyDialog = false
                        scope.launch {
                            memosViewModel.deleteFolder(folderName, deleteContents = false)
                            navController.popBackStack()
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.delete_folder),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteOnlyDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDeleteAllDialog) {
        val memoCount = memosViewModel.memos.count { it.folder == folderName }
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.delete_folder_and_contents)) },
            text = { Text(stringResource(R.string.delete_folder_and_contents_confirm, folderTitle, memoCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAllDialog = false
                        scope.launch {
                            memosViewModel.deleteFolder(folderName, deleteContents = true)
                            navController.popBackStack()
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.delete_all),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private val MemoListFabAvoidancePadding = 96.dp
