package com.elegen.elegencashbook

import android.app.Application
import com.elegen.elegencashbook.data.identity.IdentityManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ElegenApp : Application() {

    // Eagerly created so its session collector (claim-on-login, identity switch) runs from launch.
    @Inject lateinit var identityManager: IdentityManager

    override fun onCreate() {
        super.onCreate()
        identityManager.hashCode() // touch to ensure instantiation
    }
}
