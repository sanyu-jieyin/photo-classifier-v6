package com.example.photoclassifier

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SortMode {
    OLDEST_FIRST,   // 从前往后：最旧的500张
    NEWEST_FIRST    // 从后往前：最新的500张
}

class FileHelper(private val context: Context) {

    fun getPhotosFromFolder(folderUri: Uri, sortMode: SortMode = SortMode.NEWEST_FIRST): List<PhotoItem> {
        val tree = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()

        val allPhotos = tree.listFiles()
            .asSequence()
            .filter { it.isFile && it.type?.startsWith("image/") == true }
            .map { 
                PhotoItem(
                    uri = it.uri,
                    name = it.name ?: "unknown",
                    mimeType = it.type ?: "image/jpeg",
                    lastModified = it.lastModified()
                )
            }
            .toList()
            .sortedBy { it.lastModified }

        return when (sortMode) {
            SortMode.OLDEST_FIRST -> allPhotos.take(500)
            SortMode.NEWEST_FIRST -> allPhotos.takeLast(500)
        }
    }

    suspend fun movePhoto(
        sourceUri: Uri,
        sourceFolderUri: Uri,
        targetFolderUri: Uri,
        fileName: String
    ): Uri? {
        return withContext(Dispatchers.IO) {
            // 方法1: moveDocument（真正的移动，O(1)，保留所有元数据）
            try {
                val sourceTreeId = DocumentsContract.getTreeDocumentId(sourceFolderUri)
                val targetTreeId = DocumentsContract.getTreeDocumentId(targetFolderUri)
                val sourceParentUri = DocumentsContract.buildDocumentUriUsingTree(sourceFolderUri, sourceTreeId)
                val targetParentUri = DocumentsContract.buildDocumentUriUsingTree(targetFolderUri, targetTreeId)

                val movedUri = DocumentsContract.moveDocument(
                    context.contentResolver,
                    sourceUri,
                    sourceParentUri,
                    targetParentUri
                )
                if (movedUri != null) {
                    return@withContext movedUri
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 方法2: fallback 复制+删除
            try {
                val targetTree = DocumentFile.fromTreeUri(context, targetFolderUri)
                    ?: return@withContext null

                val mimeType = context.contentResolver.getType(sourceUri) ?: "image/jpeg"
                val newFile = targetTree.createFile(mimeType, fileName)
                    ?: return@withContext null

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                } ?: return@withContext null

                // 尝试保留修改时间（Android 16+ 反射调用）
                val sourceDoc = DocumentFile.fromSingleUri(context, sourceUri)
                val originalTime = sourceDoc?.lastModified()
                if (Build.VERSION.SDK_INT >= 36 && originalTime != null) {
                    trySetDocumentLastModified(newFile.uri, originalTime)
                }

                DocumentFile.fromSingleUri(context, sourceUri)?.delete()
                newFile.uri
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun trySetDocumentLastModified(uri: Uri, time: Long) {
        try {
            val method = DocumentsContract::class.java.getMethod(
                "setDocumentLastModified",
                android.content.ContentResolver::class.java,
                Uri::class.java,
                Long::class.javaPrimitiveType
            )
            method.invoke(null, context.contentResolver, uri, time)
        } catch (_: Exception) {
            // 设备不支持，静默忽略
        }
    }

    suspend fun deletePhoto(sourceUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                DocumentFile.fromSingleUri(context, sourceUri)?.delete() == true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
