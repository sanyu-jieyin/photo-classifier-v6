package com.example.photoclassifier

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PhotoItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val lastModified: Long = 0L
)

data class FolderItem(val uri: Uri, val name: String)
data class FolderSlot(val index: Int, val folderItem: FolderItem? = null)

sealed class UndoAction {
    data class Move(
        val photoName: String,
        val photoMimeType: String,
        val fromIndex: Int,
        val slotIndex: Int,
        val slotName: String,
        val sourceFolderUri: Uri,
        val targetFolderUri: Uri
    ) : UndoAction()

    data class Delete(
        val photo: PhotoItem,
        val fromIndex: Int,
        val sourceFolderUri: Uri,
        val cacheFile: File
    ) : UndoAction()
}

class PhotoClassifierViewModel(application: Application) : AndroidViewModel(application) {
    private val fileHelper = FileHelper(application)
    private val context get() = getApplication<Application>()
    private val prefs = context.getSharedPreferences("photo_classifier_v6", Context.MODE_PRIVATE)

    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    val photos: StateFlow<List<PhotoItem>> = _photos.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _slots = MutableStateFlow(List(10) { FolderSlot(it) })
    val slots: StateFlow<List<FolderSlot>> = _slots.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadProgress = MutableStateFlow(0)
    val loadProgress: StateFlow<Int> = _loadProgress.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _sourceName = MutableStateFlow<String?>(null)
    val sourceName: StateFlow<String?> = _sourceName.asStateFlow()

    private var sourceFolderUri: Uri? = null

    private val _undoStack = MutableStateFlow<List<UndoAction>>(emptyList())
    val undoStack: StateFlow<List<UndoAction>> = _undoStack.asStateFlow()

    private val _showDeleteConfirm = MutableStateFlow(false)
    val showDeleteConfirm: StateFlow<Boolean> = _showDeleteConfirm.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NEWEST_FIRST)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    init {
        restoreSlots()
    }

    private fun saveSlots() {
        val editor = prefs.edit()
        _slots.value.forEachIndexed { index, slot ->
            if (slot.folderItem != null) {
                editor.putString("slot_uri_$index", slot.folderItem.uri.toString())
                editor.putString("slot_name_$index", slot.folderItem.name)
            } else {
                editor.remove("slot_uri_$index")
                editor.remove("slot_name_$index")
            }
        }
        editor.apply()
    }

    private fun restoreSlots() {
        val restored = (0 until 10).map { index ->
            val uriStr = prefs.getString("slot_uri_$index", null)
            val name = prefs.getString("slot_name_$index", null)
            if (uriStr != null && name != null) {
                FolderSlot(index, FolderItem(Uri.parse(uriStr), name))
            } else {
                FolderSlot(index)
            }
        }
        _slots.value = restored
    }

    fun loadSourceFolder(uri: Uri, mode: SortMode = SortMode.NEWEST_FIRST) {
        viewModelScope.launch {
            _isLoading.value = true
            _loadProgress.value = 0
            sourceFolderUri = uri
            _sourceName.value = DocumentFile.fromTreeUri(context, uri)?.name
            _sortMode.value = mode

            val list = withContext(Dispatchers.IO) {
                fileHelper.getPhotosFromFolder(uri, mode)
            }

            _photos.value = list
            _currentIndex.value = 0
            _loadProgress.value = list.size
            _isLoading.value = false
            _undoStack.value = emptyList()

            if (list.size >= 500) {
                _toastMessage.value = "已加载${if (mode == SortMode.NEWEST_FIRST) "最新" else "最早"} 500 张"
            }
        }
    }

    fun setSortMode(mode: SortMode) {
        if (_sortMode.value == mode) return
        sourceFolderUri?.let { loadSourceFolder(it, mode) }
    }

    fun setSlotFolder(slotIndex: Int, folder: FolderItem) {
        _slots.value = _slots.value.map {
            if (it.index == slotIndex) it.copy(folderItem = folder) else it
        }
        saveSlots()
    }

    fun clearSlot(slotIndex: Int) {
        _slots.value = _slots.value.map {
            if (it.index == slotIndex) it.copy(folderItem = null) else it
        }
        saveSlots()
    }

    fun moveCurrentPhotoToSlot(slotIndex: Int) {
        val currentIdx = _currentIndex.value
        val photo = _photos.value.getOrNull(currentIdx) ?: return
        val slot = _slots.value.getOrNull(slotIndex) ?: return
        val targetUri = slot.folderItem?.uri ?: return
        val srcUri = sourceFolderUri ?: return

        // 立刻从本地列表移除，UI 零延迟
        val mutable = _photos.value.toMutableList()
        mutable.removeAt(currentIdx)
        _photos.value = mutable
        if (_currentIndex.value >= _photos.value.size) {
            _currentIndex.value = maxOf(0, _photos.value.size - 1)
        }

        viewModelScope.launch {
            val newUri = withContext(Dispatchers.IO) {
                fileHelper.movePhoto(photo.uri, srcUri, targetUri, photo.name)
            }
            if (newUri != null) {
                _undoStack.value = _undoStack.value + UndoAction.Move(
                    photo.name, photo.mimeType, currentIdx, slotIndex,
                    slot.folderItem.name, srcUri, targetUri
                )
                _toastMessage.value = "已移动到「${slot.folderItem.name}」"
            } else {
                // 移动失败，把照片加回来
                val restore = _photos.value.toMutableList()
                restore.add(currentIdx.coerceAtMost(restore.size), photo)
                _photos.value = restore
                _currentIndex.value = currentIdx
                _toastMessage.value = "移动失败（可能目标文件夹已有同名文件）"
            }
        }
    }

    fun deleteCurrentPhoto() {
        _showDeleteConfirm.value = true
    }

    fun confirmDeleteCurrentPhoto() {
        _showDeleteConfirm.value = false
        val currentIdx = _currentIndex.value
        val photo = _photos.value.getOrNull(currentIdx) ?: return
        val srcUri = sourceFolderUri ?: return

        // 立刻从本地列表移除，UI 零延迟
        val mutable = _photos.value.toMutableList()
        mutable.removeAt(currentIdx)
        _photos.value = mutable
        if (_currentIndex.value >= _photos.value.size) {
            _currentIndex.value = maxOf(0, _photos.value.size - 1)
        }

        viewModelScope.launch {
            val cacheDir = File(context.cacheDir, "photo_trash").apply { mkdirs() }
            val cacheFile = File(cacheDir, "${UUID.randomUUID()}_${photo.name}")

            val copied = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(photo.uri)?.use { input ->
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }

            if (copied) {
                val success = withContext(Dispatchers.IO) {
                    fileHelper.deletePhoto(photo.uri)
                }
                if (success) {
                    _undoStack.value = _undoStack.value + UndoAction.Delete(
                        photo, currentIdx, srcUri, cacheFile
                    )
                    _toastMessage.value = "已删除"
                } else {
                    cacheFile.delete()
                    // 删除失败，把照片加回来
                    val restore = _photos.value.toMutableList()
                    restore.add(currentIdx.coerceAtMost(restore.size), photo)
                    _photos.value = restore
                    _currentIndex.value = currentIdx
                    _toastMessage.value = "删除失败"
                }
            } else {
                // 备份失败，把照片加回来
                val restore = _photos.value.toMutableList()
                restore.add(currentIdx.coerceAtMost(restore.size), photo)
                _photos.value = restore
                _currentIndex.value = currentIdx
                _toastMessage.value = "删除失败（无法备份）"
            }
        }
    }

    fun dismissDeleteConfirm() {
        _showDeleteConfirm.value = false
    }

    fun undoLastAction() {
        val actions = _undoStack.value
        if (actions.isEmpty()) return
        val lastAction = actions.last()
        _undoStack.value = actions.dropLast(1)

        viewModelScope.launch {
            when (lastAction) {
                is UndoAction.Move -> {
                    val targetTree = DocumentFile.fromTreeUri(context, lastAction.targetFolderUri)
                    val movedFile = targetTree?.listFiles()?.find { it.name == lastAction.photoName }

                    if (movedFile != null) {
                        val restoredUri = withContext(Dispatchers.IO) {
                            fileHelper.movePhoto(
                                movedFile.uri,
                                lastAction.targetFolderUri,
                                lastAction.sourceFolderUri,
                                lastAction.photoName
                            )
                        }
                        if (restoredUri != null) {
                            val mutable = _photos.value.toMutableList()
                            val insertIndex = minOf(lastAction.fromIndex, mutable.size)
                            mutable.add(insertIndex, PhotoItem(restoredUri, lastAction.photoName, lastAction.photoMimeType))
                            _photos.value = mutable
                            _currentIndex.value = insertIndex
                            _toastMessage.value = "已撤销移动"
                        } else {
                            _toastMessage.value = "撤销失败"
                        }
                    } else {
                        _toastMessage.value = "撤销失败（找不到文件）"
                    }
                }
                is UndoAction.Delete -> {
                    val cacheFile = lastAction.cacheFile
                    if (!cacheFile.exists()) {
                        _toastMessage.value = "撤销失败（备份已丢失）"
                        return@launch
                    }
                    val targetTree = DocumentFile.fromTreeUri(context, lastAction.sourceFolderUri)
                        ?: return@launch
                    val mimeType = lastAction.photo.mimeType
                    val newFile = targetTree.createFile(mimeType, lastAction.photo.name)
                        ?: targetTree.createFile(mimeType, "${lastAction.photo.name}_${System.currentTimeMillis()}")
                        ?: return@launch

                    val success = withContext(Dispatchers.IO) {
                        try {
                            cacheFile.inputStream().use { input ->
                                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                                    input.copyTo(output)
                                }
                            }
                            val mutable = _photos.value.toMutableList()
                            val insertIndex = minOf(lastAction.fromIndex, mutable.size)
                            mutable.add(insertIndex, PhotoItem(newFile.uri, lastAction.photo.name, lastAction.photo.mimeType))
                            _photos.value = mutable
                            _currentIndex.value = insertIndex
                            cacheFile.delete()
                            true
                        } catch (e: Exception) {
                            e.printStackTrace()
                            false
                        }
                    }
                    _toastMessage.value = if (success) "已撤销删除" else "撤销失败"
                }
            }
        }
    }

    fun nextPhoto() {
        if (_currentIndex.value < _photos.value.size - 1) {
            _currentIndex.value++
        }
    }

    fun prevPhoto() {
        if (_currentIndex.value > 0) {
            _currentIndex.value--
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }
}
