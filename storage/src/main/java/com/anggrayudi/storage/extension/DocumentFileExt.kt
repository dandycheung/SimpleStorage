package com.anggrayudi.storage.extension

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.*
import com.anggrayudi.storage.callback.FileCallback
import com.anggrayudi.storage.callback.FileCopyCallback
import com.anggrayudi.storage.callback.FileMoveCallback
import kotlinx.coroutines.Job
import java.io.*

/**
 * Created on 16/08/20
 * @author Anggrayudi H
 */

/**
 * ID of this storage. For external storage, it will return [DocumentFileCompat.PRIMARY],
 * otherwise it is a SD Card and will return integers like `6881-2249`.
 * However, it will return empty `String` if this [DocumentFile] is picked from [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_CREATE_DOCUMENT]
 */
val DocumentFile.storageId: String
    get() = DocumentFileCompat.getStorageId(uri)

val DocumentFile.isReadOnly: Boolean
    get() = uri.authority != DocumentFileCompat.FOLDER_PICKER_AUTHORITY

/**
 * Returns `null` if this [DocumentFile] is picked from [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_CREATE_DOCUMENT]
 */
val DocumentFile.storageType: StorageType?
    get() = if (isReadOnly) null else {
        if (inPrimaryStorage) StorageType.EXTERNAL else StorageType.SD_CARD
    }

/**
 * `true` if this file located in primary storage, i.e. external storage.
 */
val DocumentFile.inPrimaryStorage: Boolean
    get() = !isReadOnly && (storageId == DocumentFileCompat.PRIMARY || isJavaFile)

/**
 * `true` if this file located in SD Card
 */
val DocumentFile.inSdCardStorage: Boolean
    get() = !isReadOnly && (storageId != DocumentFileCompat.PRIMARY && !isJavaFile)

/**
 * `true` if this file was created with [File]
 */
val DocumentFile.isJavaFile: Boolean
    get() = uri.scheme == ContentResolver.SCHEME_FILE

/**
 * Filename without extension
 */
val DocumentFile.baseName: String
    get() = name.orEmpty().substringBeforeLast('.')

/**
 * File extension
 */
val DocumentFile.extension: String
    get() = name.orEmpty().substringAfterLast('.', "")

/**
 * @return `null` if you try to read files from SD Card or you want to convert a file picked
 * from [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_CREATE_DOCUMENT]
 */
fun DocumentFile.toJavaFile(): File? {
    return when {
        isJavaFile -> File(uri.path!!)
        inPrimaryStorage -> File("${SimpleStorage.externalStoragePath}/$filePath")
        else -> null
    }
}

/**
 * @return File path without storage ID, otherwise return empty `String` if this is the root path or if this [DocumentFile] is picked
 * from [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_CREATE_DOCUMENT]
 */
val DocumentFile.filePath: String
    get() = when {
        isJavaFile -> uri.path.orEmpty().replaceFirst(SimpleStorage.externalStoragePath, "")
        isReadOnly -> ""
        else -> uri.path.orEmpty().substringAfterLast("/document/$storageId:", "")
    }

/**
 * Root path of this file.
 * * For file picked from [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_CREATE_DOCUMENT], it will return empty `String`
 * * For file stored in external or primary storage, it will return [SimpleStorage.externalStoragePath].
 * * For file stored in SD Card, it will return integers like `6881-2249:`
 */
val DocumentFile.rootPath: String
    get() = when {
        isReadOnly -> ""
        inSdCardStorage -> "$storageId:"
        else -> SimpleStorage.externalStoragePath
    }

/**
 * @return For file in SD Card: `6881-2249:Music/song.mp3`, otherwise `/storage/emulated/0/Music/song.mp3`
 */
val DocumentFile.fullPath: String
    get() = when {
        isReadOnly -> ""
        isJavaFile -> uri.path.orEmpty()
        inPrimaryStorage -> "${SimpleStorage.externalStoragePath}/$filePath"
        else -> "$storageId:$filePath"
    }

/**
 * Delete this file and create new empty file using previous `filename` and `mimeType`.
 * It cannot be applied if current [DocumentFile] is a directory.
 */
fun DocumentFile.recreateFile(): DocumentFile? {
    return if (isReadOnly || isDirectory) {
        null
    } else {
        val filename = name.orEmpty()
        val mimeType = type ?: DocumentFileCompat.MIME_TYPE_UNKNOWN
        val parentFile = parentFile
        delete()
        parentFile?.createFile(mimeType, filename)
    }
}

fun DocumentFile.getRootDocumentFile(context: Context) = if (isReadOnly) null else DocumentFileCompat.getRootDocumentFile(context, storageId)

/**
 * @return `true` if this file has read and write access, or this file has URI permission for read and write access.
 */
val DocumentFile.isAccessible: Boolean
    get() = canRead() && canWrite() && !isReadOnly

fun DocumentFile.isRootUriPermissionGranted(context: Context): Boolean {
    return !isReadOnly && DocumentFileCompat.isStorageUriPermissionGranted(context, storageId)
}

/**
 * Useful for creating temporary files. The extension is `*.bin`
 */
fun DocumentFile.createBinaryFile(
    name: String,
    appendBinFileExtension: Boolean = true,
    mimeType: String = DocumentFileCompat.MIME_TYPE_BINARY_FILE
): DocumentFile? {
    return when {
        isReadOnly || isFile -> null
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
            val filename = if (appendBinFileExtension) {
                if (name.endsWith(".bin")) name else "$name.bin"
            } else {
                name
            }
            createFile(mimeType, filename)
        }
        else -> {
            val filename = name.removeSuffix(".bin")
            createFile(mimeType, filename)?.apply {
                if (!appendBinFileExtension) {
                    renameTo(name)
                }
            }
        }
    }
}

// TODO: 20/08/20 Create searchFile(fileOnlyFilter recursive: Boolean = false)

/**
 * @param append if `false` and the file already exists, it will recreate the file.
 */
@WorkerThread
fun DocumentFile.openOutputStream(context: Context, append: Boolean = true): OutputStream? {
    return try {
        if (isJavaFile) {
            FileOutputStream(toJavaFile(), append)
        } else {
            context.contentResolver.openOutputStream(uri, if (append && !isReadOnly) "wa" else "w")
        }
    } catch (e: FileNotFoundException) {
        null
    }
}

@WorkerThread
fun DocumentFile.openInputStream(context: Context): InputStream? {
    return try {
        if (isJavaFile) {
            // handle file from internal storage
            FileInputStream(toJavaFile())
        } else {
            context.contentResolver.openInputStream(uri)
        }
    } catch (e: FileNotFoundException) {
        null
    }
}

@UiThread
fun DocumentFile.openFileIntent(context: Context, authority: String) = Intent(Intent.ACTION_VIEW)
    .setData(if (isJavaFile) FileProvider.getUriForFile(context, authority, File(uri.path!!)) else uri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

@WorkerThread
fun DocumentFile.copyTo(context: Context, targetStorageId: String, targetFolderPath: String, callback: FileCopyCallback? = null) {
    if (targetStorageId == storageId && targetFolderPath == parentFile?.filePath) {
        callback?.onFailed(ErrorCode.TARGET_FOLDER_CANNOT_HAVE_SAME_PATH_WITH_SOURCE_FOLDER)
        return
    }

    if (!isFile) {
        callback?.onFailed(ErrorCode.SOURCE_FILE_NOT_FOUND)
        return
    }

    try {
        if (callback?.onCheckFreeSpace(DocumentFileCompat.getFreeSpace(context, targetStorageId), length()) == false) {
            callback.onFailed(ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH)
            return
        }
    } catch (e: Throwable) {
        callback?.onFailed(ErrorCode.STORAGE_PERMISSION_DENIED)
        return
    }

    val reportInterval = callback?.onStartCopying() ?: 0
    val watchProgress = reportInterval > 0
    try {
        val targetFile = createTargetFile(context, targetStorageId, targetFolderPath, callback) ?: return
        createFileStreams(context, this, targetFile, callback) { inputStream, outputStream ->
            copyFileStream(inputStream, outputStream, targetFile, watchProgress, reportInterval, callback)
        }
    } catch (e: SecurityException) {
        callback?.onFailed(ErrorCode.STORAGE_PERMISSION_DENIED)
    } catch (e: InterruptedIOException) {
        callback?.onFailed(ErrorCode.CANCELLED)
    } catch (e: InterruptedException) {
        callback?.onFailed(ErrorCode.CANCELLED)
    } catch (e: IOException) {
        callback?.onFailed(ErrorCode.UNKNOWN_IO_ERROR)
    }
}

private inline fun createFileStreams(
    context: Context,
    sourceFile: DocumentFile,
    targetFile: DocumentFile,
    callback: FileCallback?,
    onStreamsReady: (InputStream, OutputStream) -> Unit
) {
    val outputStream = targetFile.openOutputStream(context)
    if (outputStream == null) {
        callback?.onFailed(ErrorCode.TARGET_FILE_NOT_FOUND)
        return
    }

    val inputStream = sourceFile.openInputStream(context)
    if (inputStream == null) {
        callback?.onFailed(ErrorCode.SOURCE_FILE_NOT_FOUND)
        outputStream.closeStream()
        return
    }

    onStreamsReady(inputStream, outputStream)
}

private fun DocumentFile.createTargetFile(
    context: Context,
    targetStorageId: String,
    targetFolderPath: String,
    callback: FileCallback?
): DocumentFile? {
    try {
        val targetFolder = DocumentFileCompat.mkdirs(context, targetStorageId, targetFolderPath)
        if (targetFolder == null) {
            callback?.onFailed(ErrorCode.STORAGE_PERMISSION_DENIED)
            return null
        }

        var targetFile = targetFolder.findFile(name.orEmpty())
        if (targetFile?.isFile == true) {
            callback?.onFailed(ErrorCode.TARGET_FILE_EXISTS)
            return null
        }

        targetFile = targetFolder.createFile(type ?: DocumentFileCompat.MIME_TYPE_UNKNOWN, name.orEmpty())
        if (targetFile == null) {
            callback?.onFailed(ErrorCode.CANNOT_CREATE_FILE_IN_TARGET)
        }
    } catch (e: SecurityException) {
        callback?.onFailed(ErrorCode.STORAGE_PERMISSION_DENIED)
    } catch (e: InterruptedIOException) {
        callback?.onFailed(ErrorCode.CANCELLED)
    } catch (e: InterruptedException) {
        callback?.onFailed(ErrorCode.CANCELLED)
    } catch (e: IOException) {
        callback?.onFailed(ErrorCode.UNKNOWN_IO_ERROR)
    }
    return null
}

private fun DocumentFile.copyFileStream(
    inputStream: InputStream,
    outputStream: OutputStream,
    targetFile: DocumentFile,
    watchProgress: Boolean,
    reportInterval: Long,
    callback: FileCallback?
) {
    var timer: Job? = null
    try {
        var byteMoved = 0L
        var writeSpeed = 0
        val srcSize = length()
        // using timer on small file is useless. We set minimum 10MB.
        if (watchProgress && callback != null && srcSize > 10 * FileSize.MB) {
            timer = startCoroutineTimer(repeatMillis = reportInterval) {
                callback.onReport(byteMoved * 100f / srcSize, byteMoved, writeSpeed)
                writeSpeed = 0
            }
        }
        val buffer = ByteArray(1024)
        var read = inputStream.read(buffer)
        while (read != -1) {
            outputStream.write(buffer, 0, read)
            byteMoved += read
            writeSpeed += read
            read = inputStream.read(buffer)
        }
        timer?.cancel()
        if (callback is FileCopyCallback && callback.onCompleted(targetFile) || callback is FileMoveCallback) {
            delete()
        }
        if (callback is FileMoveCallback) {
            callback.onCompleted(targetFile)
        }
    } finally {
        timer?.cancel()
        inputStream.closeStream()
        outputStream.closeStream()
    }
}

@WorkerThread
fun DocumentFile.moveTo(context: Context, targetStorageId: String, targetFolderPath: String, callback: FileMoveCallback? = null) {
    if (targetStorageId == storageId && targetFolderPath == parentFile?.filePath) {
        callback?.onFailed(ErrorCode.TARGET_FOLDER_CANNOT_HAVE_SAME_PATH_WITH_SOURCE_FOLDER)
        return
    }

    if (!isFile) {
        callback?.onFailed(ErrorCode.SOURCE_FILE_NOT_FOUND)
        return
    }

    if (inPrimaryStorage && targetStorageId == DocumentFileCompat.PRIMARY) {
        val sourceFile = File(uri.path!!)
        val targetFile = File(SimpleStorage.externalStoragePath + "/" + targetFolderPath, name.orEmpty())
        if (sourceFile.renameTo(targetFile)) {
            callback?.onCompleted(DocumentFile.fromFile(targetFile))
            return
        }
    }

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && inSdCardStorage
            && targetStorageId != DocumentFileCompat.PRIMARY && storageId == targetStorageId
        ) {
            val targetDocumentUri = DocumentFileCompat.createDocumentUri(targetStorageId, targetFolderPath)
            val movedFileUri = DocumentsContract.moveDocument(context.contentResolver, uri, parentFile!!.uri, targetDocumentUri)
            if (movedFileUri != null) {
                // TODO: 18/08/20 Check if fromTreeUri() usage is corrent
                val newFile = DocumentFile.fromTreeUri(context, movedFileUri)
                if (newFile != null && newFile.isFile) {
                    callback?.onCompleted(newFile)
                } else {
                    callback?.onFailed(ErrorCode.TARGET_FILE_NOT_FOUND)
                }
                return
            }
        }

        if (callback?.onCheckFreeSpace(DocumentFileCompat.getFreeSpace(context, targetStorageId), length()) == false) {
            callback.onFailed(ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH)
            return
        }
    } catch (e: Throwable) {
        callback?.onFailed(ErrorCode.STORAGE_PERMISSION_DENIED)
        return
    }

    val reportInterval = callback?.onStartMoving() ?: 0
    val watchProgress = reportInterval > 0

    try {
        val targetFile = createTargetFile(context, targetStorageId, targetFolderPath, callback) ?: return
        createFileStreams(context, this, targetFile, callback) { inputStream, outputStream ->
            copyFileStream(inputStream, outputStream, targetFile, watchProgress, reportInterval, callback)
        }
    } catch (e: SecurityException) {
        callback?.onFailed(ErrorCode.STORAGE_PERMISSION_DENIED)
    } catch (e: InterruptedIOException) {
        callback?.onFailed(ErrorCode.CANCELLED)
    } catch (e: InterruptedException) {
        callback?.onFailed(ErrorCode.CANCELLED)
    } catch (e: IOException) {
        callback?.onFailed(ErrorCode.UNKNOWN_IO_ERROR)
    }
}