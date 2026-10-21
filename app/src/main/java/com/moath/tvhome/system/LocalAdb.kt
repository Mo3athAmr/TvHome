package com.moath.tvhome.system

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Presses remote keys for us through the TV's own network debugging (adbd on port 5555), for the one
 * thing accessibility can't do on a TV without a touchscreen: a key press inside another app (Casper's
 * channel list opens only on OK). The first use shows the system's "Allow debugging?" prompt once.
 */
object LocalAdb {
    private const val TAG = "LocalAdb"
    private const val PORT = 5555

    private fun keys(context: Context): AdbKeyPair {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Sends one key press (e.g. 23 = DPAD_CENTER). False when debugging is off or not yet allowed. */
    suspend fun key(context: Context, keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(20_000) {
            runCatching {
                Dadb.create("127.0.0.1", PORT, keys(context)).use { it.shell("input keyevent $keyCode").exitCode == 0 }
            }.onFailure { Log.w(TAG, "key $keyCode failed", it) }.getOrDefault(false)
        } ?: false
    }
}
