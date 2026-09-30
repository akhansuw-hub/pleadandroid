// Android counterpart of the process-level setup in ArgueWin/App/ArgueWinApp.swift + AppDelegate.swift.
// Wave 1 keeps it minimal; later waves register their process-wide services here (RevenueCat, AppsFlyer
// `Attribution.configure()`, notification channels / PushService), each guarded by its AppConfig key.
package app.plead.android.app

import android.app.Application

class PleadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        /** The process's Application, for services that need a Context (DataStore, notifications, widgets). */
        lateinit var instance: PleadApplication
            private set
    }
}
