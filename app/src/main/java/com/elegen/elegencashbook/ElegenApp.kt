package com.elegen.elegencashbook

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.elegen.elegencashbook.data.identity.IdentityManager
import com.elegen.elegencashbook.data.local.prefs.AppPreferences
import com.elegen.elegencashbook.domain.repository.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class ElegenApp : Application() {

    // Eagerly created so its session collector (claim-on-login, identity switch) runs from launch.
    @Inject lateinit var identityManager: IdentityManager
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var appPreferences: AppPreferences

    override fun onCreate() {
        super.onCreate()
        identityManager.hashCode() // touch to ensure instantiation
        // Must apply before any Activity inflates, so a synchronous read is the lesser evil here.
        AppCompatDelegate.setDefaultNightMode(
            if (runBlocking { appPreferences.darkThemeNow() }) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        // Drains anything still PENDING from a prior session the app never got to push (e.g. was
        // killed before a network-constrained worker could run) — no-ops if guest/unconfigured.
        syncScheduler.requestPush()
        syncScheduler.requestPull()
        syncScheduler.schedulePeriodicPull()
        syncScheduler.scheduleCleanup()
    }
}
