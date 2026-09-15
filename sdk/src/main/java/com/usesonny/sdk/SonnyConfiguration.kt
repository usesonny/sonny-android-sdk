package com.usesonny.sdk

import java.net.URLEncoder
import java.net.URI

/** Public app identifiers. Customer identity JWTs are passed separately to identify(). */
data class SonnyConfiguration(
    val siteId: String,
    val appKey: String,
    val origin: String = "https://www.usesonny.com",
) {
    init {
        require(siteId.matches(Regex("^[\\w-]{1,128}$")) && appKey.matches(Regex("^[\\w-]{1,128}$"))) {
            "Sonny siteId and appKey must be the identifiers from widget settings"
        }
        val parsed = URI(origin)
        require(parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.userInfo == null
            && parsed.query == null && parsed.fragment == null && parsed.path.isNullOrEmpty()) {
            "Sonny origin must be an HTTPS origin without a path, credentials or query"
        }
    }

    val embedUrl: String
        get() = "$origin/embed/widget?siteId=${encode(siteId)}&appKey=${encode(appKey)}&sdk=${encode("android/${BuildConfig.SDK_VERSION}")}"

    fun acceptsBridgeMessage(sourceOrigin: String, isMainFrame: Boolean): Boolean =
        isMainFrame && sourceOrigin == origin

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
