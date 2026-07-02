package com.elegen.elegencashbook.core.logging

import android.util.Log
import com.elegen.elegencashbook.BuildConfig

/**
 * Single logging facade (spec §10). Feature code must use this — never android.util.Log directly.
 *
 * Rules (constitution §6, spec §9):
 *  - debug() is compiled-in but silenced in release builds.
 *  - NEVER pass balances, amounts, user ids, emails, phones, or tokens in messages.
 */
interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

/** Default implementation backed by logcat. Injected via Hilt from P2 on; usable directly until then. */
class AndroidLogger : Logger {
    override fun debug(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    override fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
