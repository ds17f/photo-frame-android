package com.photoframe.settings

import java.net.URI

/** Whether the remote dashboard is hidden, opened by a tap, or continuously displayed. */
enum class DashboardMode { OFF, ON_TAP, ALWAYS }

/** Remote dashboards must use HTTPS and must not embed credentials in their URL. */
fun isValidDashboardUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port in 1..65535)
}.getOrDefault(false)
