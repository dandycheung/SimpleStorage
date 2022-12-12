package com.anggrayudi.storage.file

import android.content.Context
import android.os.Environment

/**
 * Created on 03/06/21
 * @author Anggrayudi H
 */
object StorageId {

    /**
     * For files under [Environment.getExternalStorageDirectory]
     */
    const val PRIMARY = "primary"

    /**
     * For files under [Context.getFilesDir] or [Context.getDataDir].
     * It is not really a storage ID, and can't be used in file tree URI.
     */
    const val DATA = "data"

    /**
     * To access SD card in Kitkat, use `sdcard` as the storage ID, instead of the actual ID like `15FA-160C`
     */
    const val KITKAT_SDCARD = "sdcard"
}