package com.example.photoclassifier

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoClassifierApp() {
    val context = LocalContext.current
    val viewModel: PhotoClassifierViewModel = viewModel()

    val photos by viewModel.photos.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val sourceName by viewModel.sourceName.collectAsState()
    val loadProgress by viewModel.loadProgress.collectAsState()
    val undoStack by viewModel.undoStack.collectAsState()
    val showDeleteConfirm by viewModel.showDeleteConfirm.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()

    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var imageCenter by remember { mutableStateOf(Offset.Zero) }
    val slotRects = remember { mutableStateListOf<Rect?>(null, null, null, null, null, null, null, null, null, null) }
    var highlightedSlot by remember { mutableIntStateOf(-1) }

    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 0.88f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "scale"
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                permissionLauncher.launch(arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                permissionLauncher.launch(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES))
            }
            else -> {
                permissionLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }
    }

    val sourceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.loadSourceFolder(uri)
            }
        }
    }

    var activeSlotIndex by remember { mutableIntStateOf(-1) }
    val slotFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && activeSlotIndex >= 0) {
            result.data?.data?.let { uri ->
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                tree?.let {
                    viewModel.setSlotFolder(
                        activeSlotIndex,
                        FolderItem(uri, it.name ?: "未命名")
                    )
                }
            }
        }
        activeSlotIndex = -1
    }

    // Toast 显示 3.5 秒
    toastMessage?.let { message ->
        LaunchedEffect(message) {
            delay(3500)
            viewModel.clearToast()
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirm() },
            title = { Text("确认删除") },
            text = { Text("确定要删除当前图片吗？此操作可通过撤销恢复。") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmDeleteCurrentPhoto() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirm() }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = sourceName ?: "未选择文件夹",
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (photos.isEmpty()) "0 / 0" else "${currentIndex + 1} / ${photos.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        sourceLauncher.launch(intent)
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "选择文件夹")
                    }
                },
                actions = {
                    // 排序切换
                    if (photos.isNotEmpty()) {
                        IconButton(onClick = {
                            viewModel.setSortMode(
                                if (sortMode == SortMode.NEWEST_FIRST) SortMode.OLDEST_FIRST else SortMode.NEWEST_FIRST
                            )
                        }) {
                            Icon(
                                imageVector = if (sortMode == SortMode.NEWEST_FIRST)
                                    Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                contentDescription = "切换排序",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.undoLastAction() },
                        enabled = undoStack.isNotEmpty()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "撤销",
                            tint = if (undoStack.isNotEmpty())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.deleteCurrentPhoto() },
                        enabled = photos.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = if (photos.isNotEmpty())
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            )
        },
        bottomBar = {
            BottomSlotBar(
                slots = slots,
                isDragging = isDragging,
                highlightedSlot = highlightedSlot,
                onSlotRectChange = { idx, rect ->
                    if (idx < slotRects.size) slotRects[idx] = rect
                },
                onSlotClick = { idx ->
                    activeSlotIndex = idx
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    slotFolderLauncher.launch(intent)
                },
                onSlotLongClick = { idx ->
                    viewModel.clearSlot(idx)
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "正在扫描图片...",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        if (loadProgress > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "已找到 $loadProgress 张",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                photos.isEmpty() -> {
                    EmptyState(onSelectFolder = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        sourceLauncher.launch(intent)
                    })
                }
                else -> {
                    PhotoGallery(
                        photos = photos,
                        currentIndex = currentIndex,
                        isDragging = isDragging,
                        dragOffset = dragOffset,
                        dragScale = dragScale,
                        imageCenter = imageCenter,
                        onImageCenterChange = { imageCenter = it },
                        onDragStart = {
                            isDragging = true
                            dragOffset = Offset.Zero
                        },
                        onDrag = { offset ->
                            dragOffset = offset
                            val dropPos = imageCenter + offset
                            highlightedSlot = slotRects.indexOfFirst {
                                it != null && it.contains(dropPos)
                            }
                        },
                        onDragEnd = {
                            val dropPos = imageCenter + dragOffset
                            slotRects.forEachIndexed { idx, rect ->
                                if (rect != null && rect.contains(dropPos)) {
                                    viewModel.moveCurrentPhotoToSlot(idx)
                                }
                            }
                            isDragging = false
                            dragOffset = Offset.Zero
                            highlightedSlot = -1
                        },
                        onSwipeLeft = { viewModel.nextPhoto() },
                        onSwipeRight = { viewModel.prevPhoto() }
                    )
                }
            }

            // 更明显、更久的 Toast
            toastMessage?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 20.dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoGallery(
    photos: List<PhotoItem>,
    currentIndex: Int,
    isDragging: Boolean,
    dragOffset: Offset,
    dragScale: Float,
    imageCenter: Offset,
    onImageCenterChange: (Offset) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
) {
    val context = LocalContext.current
    var localDragOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startPos = down.position
                    var totalX = 0f
                    var totalY = 0f
                    var directionSet = false
                    var isHorizontal = false
                    var lastChange = down

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        lastChange = change

                        val pos = change.position
                        totalX = pos.x - startPos.x
                        totalY = pos.y - startPos.y

                        if (!directionSet) {
                            val dist = hypot(totalX, totalY)
                            if (dist > 20f) {
                                directionSet = true
                                isHorizontal = abs(totalX) > abs(totalY)
                                if (!isHorizontal) {
                                    onDragStart()
                                }
                            }
                        }

                        if (directionSet && !isHorizontal) {
                            change.consume()
                            localDragOffset = Offset(totalX, totalY)
                            onDrag(localDragOffset)
                        }
                    }

                    if (directionSet) {
                        if (isHorizontal) {
                            if (totalX > 80f) onSwipeRight()
                            else if (totalX < -80f) onSwipeLeft()
                        } else {
                            onDragEnd()
                            localDragOffset = Offset.Zero
                        }
                    }
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧预览 - 扑克牌效果加强
            Box(
                modifier = Modifier
                    .weight(0.13f)
                    .fillMaxHeight()
                    .padding(start = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                if (currentIndex > 0) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(photos[currentIndex - 1].uri)
                            .size(width = 200, height = 300)
                            .crossfade(false)
                            .build(),
                        contentDescription = "上一张",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.7f)
                            .clip(RoundedCornerShape(14.dp))
                            .alpha(0.35f)
                            .graphicsLayer {
                                rotationZ = -10f
                                rotationY = 20f
                                cameraDistance = 10f * density
                            }
                            .shadow(4.dp, RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // 中间主图
            Box(
                modifier = Modifier
                    .weight(0.74f)
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = currentIndex,
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { width -> direction * width } + fadeIn(animationSpec = tween(250))) togetherWith
                        (slideOutHorizontally { width -> -direction * width } + fadeOut(animationSpec = tween(250)))
                    },
                    label = "photo_slide"
                ) { index ->
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(photos[index].uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "当前图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .alpha(if (isDragging) 0.18f else 1f)
                            .scale(if (isDragging) 0.94f else 1f)
                            .shadow(if (isDragging) 2.dp else 8.dp, RoundedCornerShape(24.dp))
                            .onGloballyPositioned { coordinates: LayoutCoordinates ->
                                val pos = coordinates.positionInRoot()
                                val size = coordinates.size
                                onImageCenterChange(
                                    Offset(
                                        pos.x + size.width / 2f,
                                        pos.y + size.height / 2f
                                    )
                                )
                            },
                        contentScale = ContentScale.Fit
                    )
                }

                if (isDragging) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(photos[currentIndex].uri)
                            .size(300, 400)
                            .crossfade(false)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .offset {
                                IntOffset(dragOffset.x.toInt(), dragOffset.y.toInt())
                            }
                            .size(140.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                            .scale(dragScale)
                            .shadow(12.dp, RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // 右侧预览 - 扑克牌效果加强
            Box(
                modifier = Modifier
                    .weight(0.13f)
                    .fillMaxHeight()
                    .padding(end = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                if (currentIndex < photos.size - 1) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(photos[currentIndex + 1].uri)
                            .size(width = 200, height = 300)
                            .crossfade(false)
                            .build(),
                        contentDescription = "下一张",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.7f)
                            .clip(RoundedCornerShape(14.dp))
                            .alpha(0.35f)
                            .graphicsLayer {
                                rotationZ = 10f
                                rotationY = -20f
                                cameraDistance = 10f * density
                            }
                            .shadow(4.dp, RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@Composable
fun BottomSlotBar(
    slots: List<FolderSlot>,
    isDragging: Boolean,
    highlightedSlot: Int,
    onSlotRectChange: (Int, Rect?) -> Unit,
    onSlotClick: (Int) -> Unit,
    onSlotLongClick: (Int) -> Unit
) {
    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isDragging) "拖到文件夹上松开" else "左右滑动切换 · 长按图片拖拽 · 点击文件夹切换 · 长按清除",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            // 第一排 - 显示3.5个，可滑动
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(slots.take(5), key = { _, slot -> slot.index }) { idx, slot ->
                    SlotCard(
                        slot = slot,
                        isHighlighted = isDragging && highlightedSlot == idx,
                        modifier = Modifier
                            .width(88.dp)
                            .onGloballyPositioned { coordinates: LayoutCoordinates ->
                                val pos = coordinates.positionInRoot()
                                val size = coordinates.size
                                onSlotRectChange(
                                    idx,
                                    Rect(
                                        pos.x,
                                        pos.y,
                                        pos.x + size.width,
                                        pos.y + size.height
                                    )
                                )
                            },
                        onClick = { onSlotClick(idx) },
                        onLongClick = { onSlotLongClick(idx) }
                    )
                }
            }
            // 第二排 - 显示3.5个，可滑动
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(slots.drop(5), key = { _, slot -> slot.index }) { localIdx, slot ->
                    val idx = localIdx + 5
                    SlotCard(
                        slot = slot,
                        isHighlighted = isDragging && highlightedSlot == idx,
                        modifier = Modifier
                            .width(88.dp)
                            .onGloballyPositioned { coordinates: LayoutCoordinates ->
                                val pos = coordinates.positionInRoot()
                                val size = coordinates.size
                                onSlotRectChange(
                                    idx,
                                    Rect(
                                        pos.x,
                                        pos.y,
                                        pos.x + size.width,
                                        pos.y + size.height
                                    )
                                )
                            },
                        onClick = { onSlotClick(idx) },
                        onLongClick = { onSlotLongClick(idx) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotCard(
    slot: FolderSlot,
    isHighlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val borderWidth = if (isHighlighted) 2.5.dp else 1.dp
    val borderColor = when {
        isHighlighted -> MaterialTheme.colorScheme.primary
        slot.folderItem != null -> Color.Transparent
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    val bgColor = when {
        isHighlighted -> MaterialTheme.colorScheme.primaryContainer
        slot.folderItem != null -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .height(70.dp)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isHighlighted) 6.dp else 2.dp
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (slot.folderItem == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "选择",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = slot.folderItem.name,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(onSelectFolder: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "选择文件夹开始整理图片",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "支持 JPG、PNG、GIF、WebP、HEIC 等格式",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onSelectFolder,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text("选择文件夹", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
