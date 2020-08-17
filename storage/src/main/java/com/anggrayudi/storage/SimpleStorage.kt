package com.anggrayudi.storage

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.callback.StoragePermissionCallback
import com.anggrayudi.storage.extension.startActivityForResultSafely

/**
 * @author Anggrayudi Hardiannico A. (anggrayudi.hardiannico@dana.id)
 * @version SimpleStorage, v 0.0.1 09/08/20 19.08 by Anggrayudi Hardiannico A.
 */
class SimpleStorage(private val activity: Activity) {

    var storageAccessCallback: StoragePermissionCallback? = null

    private var requestCode = 0

    /**
     * It returns an intent to be dispatched via startActivityResult
     */
    private fun externalStorageRootAccessIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sm = activity.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.primaryStorageVolume.createOpenDocumentTreeIntent()
        } else {
            defaultExternalStorageAccessIntent
        }
    }

    /**
     * It returns an intent to be dispatched via startActivityResult to access to
     * the first removable no primary storage. This method requires at least Nougat
     * because on previous Android versions there's no reliable way to get the
     * volume/path of SdCard, and no, SdCard != External Storage.
     *
     * @return Null if no storage is found, the intent object otherwise
     */
    @Suppress("DEPRECATION")
    @RequiresApi(api = Build.VERSION_CODES.N)
    private fun sdCardRootAccessIntent(): Intent {
        val sm = activity.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.storageVolumes.firstOrNull { it.isRemovable }?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.createOpenDocumentTreeIntent()
            } else {
                //Access to the entire volume is only available for non-primary volumes
                if (it.isPrimary) {
                    defaultExternalStorageAccessIntent
                } else {
                    it.createAccessIntent(null)
                }
            }
        } ?: defaultExternalStorageAccessIntent
    }

    private val defaultExternalStorageAccessIntent: Intent
        get() = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (Build.VERSION.SDK_INT >= 26) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentFileCompat.createDocumentUri(DocumentFileCompat.PRIMARY))
            }
        }

    /**
     * Even though storage permission has been granted via [hasStoragePermission], read and write access may have not been granted yet.
     *
     * @param storageId Use [DocumentFileCompat.PRIMARY] for external storage. Or use SD Card storage ID.
     * @return `true` if storage pemissions and URI permissions are granted for read and write access.
     * @see [DocumentFileCompat.getStorageIds]
     */
    fun isStorageAccessGranted(storageId: String) = DocumentFileCompat.isAccessGranted(activity, storageId)

    /**
     * Managing files in direct storage requires root access. Thus we need to make sure users select root path.
     *
     * @param initialRootPath It will open [StorageType.EXTERNAL] instead for API 23 and lower, and when no SD Card inserted.
     */
    fun requestStorageAccess(requestCode: Int, initialRootPath: StorageType = StorageType.EXTERNAL) {
        if (initialRootPath == StorageType.EXTERNAL && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val root = DocumentFileCompat.getRootDocumentFile(activity, DocumentFileCompat.PRIMARY) ?: return
            saveUriPermission(root.uri)
            storageAccessCallback?.onRootPathPermissionGranted(root)
            return
        }

        val intent = if (initialRootPath == StorageType.SD_CARD && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sdCardRootAccessIntent()
        } else {
            externalStorageRootAccessIntent()
        }
        activity.startActivityForResultSafely(requestCode, intent)
        this.requestCode = requestCode
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (this.requestCode != requestCode) {
            return
        }
        if (resultCode != Activity.RESULT_OK) {
            storageAccessCallback?.onStoragePermissionDenied()
            return
        }
        val uri = data?.data ?: return
        val storageId = getStorageId(uri)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && storageId == DocumentFileCompat.PRIMARY) {
            saveUriPermission(uri)
            storageAccessCallback?.onRootPathPermissionGranted(DocumentFile.fromTreeUri(activity, uri) ?: return)
            return
        }
        if (isRootUri(uri)) {
            if (saveUriPermission(uri)) {
                storageAccessCallback?.onRootPathPermissionGranted(DocumentFile.fromTreeUri(activity, uri) ?: return)
            } else {
                storageAccessCallback?.onStoragePermissionDenied()
            }
        } else {
            val rootPath = if (storageId == DocumentFileCompat.PRIMARY) {
                externalStoragePath
            } else {
                "$storageId:"
            }
            storageAccessCallback?.onRootPathNotSelected(rootPath)
        }
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(REQUEST_CODE_STORAGE_ACCESS, requestCode)
    }

    fun onRestoreInstanceState(savedInstanceState: Bundle) {
        requestCode = savedInstanceState.getInt(REQUEST_CODE_STORAGE_ACCESS)
    }

    private fun saveUriPermission(root: Uri): Boolean {
        return try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            activity.contentResolver.takePersistableUriPermission(root, takeFlags)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun isStorageUriPermissionGranted(storageId: String): Boolean {
        val root = DocumentFileCompat.createDocumentUri(storageId)
        return activity.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.isWritePermission && it.uri == root }
    }

    companion object {

        private const val REQUEST_CODE_STORAGE_ACCESS = BuildConfig.LIBRARY_PACKAGE_NAME + ".requestCodeStorageAccess"

        @Suppress("DEPRECATION")
        val externalStoragePath: String
            get() = Environment.getExternalStorageDirectory().absolutePath

        fun hasStoragePermission(context: Context): Boolean {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        fun isRootUri(uri: Uri): Boolean {
            val path = uri.path ?: return false
            return path.indexOf(':') == path.length - 1
        }

        /**
         * If given [Uri] with path `tree/primary:Downloads/MyVideo.mp4`, then return `primary`
         */
        fun getStorageId(uri: Uri): String = if (uri.scheme == ContentResolver.SCHEME_FILE) {
            DocumentFileCompat.PRIMARY
        } else {
            uri.path!!.substringBefore(':').substringAfter('/')
        }
    }
}