package com.example.photoclassifier

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SortMode {
    NEWEST_FIRST,
    OLDEST_FIRST
}

class FileHelper(private val context: Context) {

    fun getPhotosFromFolder(folderUri: Uri, sortMode: SortMode): List<PhotoItem> {
        val treeId = DocumentsContract.getTreeDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: return emptyList()

        cursor.use {
            if (it.count == 0) return emptyList()

            val idCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val timeCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            val items = ArrayList<PhotoItem>(it.count)

            while (it.moveToNext()) {
                val mime = it.getString(mimeCol) ?: continue
                if (!mime.startsWith("image/")) continue

                val id = it.getString(idCol)
                val name = it.getString(nameCol) ?: "unknown"
                val time = it.getLong(timeCol)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, id)

                items.add(PhotoItem(uri = docUri, name = name, mimeType = mime, lastModified = time))
            }

            val sorted = items.sortedBy { it.lastModified }
            return when (sortMode) {
                SortMode.NEWEST_FIRST -> sorted.asReversed().take(500)
                SortMode.OLDEST_FIRST -> sorted.take(500)
            }
        }
    }

    suspend fun movePhoto(
        sourceUri: Uri,
        sourceFolderUri: Uri,
        targetFolderUri: Uri,
        fileName: String
    ): Uri? {
        return withContext(Dispatchers.IO) {
            // 方法1: moveDocument
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
                } ?: run {
                    newFile.delete()
                    return@withContext null
                }

                val sourceDoc = DocumentFile.fromSingleUri(context, sourceUri)
                val originalTime = sourceDoc?.lastModified()
                if (Build.VERSION.SDK_INT >= 36 && originalTime != null) {
                    trySetDocumentLastModified(newFile.uri, originalTime)
                }

                val deleted = DocumentFile.fromSingleUri(context, sourceUri)?.delete() == true
                if (!deleted) {
                    newFile.delete()
                    return@withContext null
                }

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
