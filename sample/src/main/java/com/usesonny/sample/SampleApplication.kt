package com.usesonny.sample

import android.app.Application
import com.usesonny.sdk.Sonny

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = getSharedPreferences("sample", MODE_PRIVATE)
        val siteId = settings.getString("siteId", "").orEmpty()
        val appKey = settings.getString("appKey", "").orEmpty()
        if (siteId.isNotBlank() && appKey.isNotBlank()) {
            Sonny.configure(this, siteId, appKey, settings.getString("origin", "https://www.usesonny.com")!!)
        }
    }
}
