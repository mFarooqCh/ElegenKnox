package com.elegen.elegencashbook

import android.app.Application
import com.elegen.elegencashbook.data.identity.IdentityManager
import com.elegen.elegencashbook.domain.repository.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ElegenApp : Application() {

    // Eagerly created so its session collector (claim-on-login, identity switch) runs from launch.
    @Inject lateinit var identityManager: IdentityManager
    @Inject lateinit var syncScheduler: SyncScheduler

    override fun onCreate() {
        super.onCreate()
        identityManager.hashCode() // touch to ensure instantiation
        // Drains anything still PENDING from a prior session the app never got to push (e.g. was
        // killed before a network-constrained worker could run) — no-ops if guest/unconfigured.
        syncScheduler.requestPush()
    }
}
