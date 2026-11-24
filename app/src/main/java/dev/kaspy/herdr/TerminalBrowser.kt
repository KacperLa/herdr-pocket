package dev.kaspy.herdr

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Resolve only a general web handler before giving any app the actual terminal URL. */
internal fun openTerminalBrowser(context: Context, link: TerminalWebLink): Boolean {
    val pm = context.packageManager
    // Resolving this reserved hostname is local Package Manager work. It is never navigated to.
    val probe = webIntent(Uri.parse("${link.scheme}://browser-check.invalid/"))
    val handlers = pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(
        (PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER).toLong(),
    )).mapNotNull {
        val filter = it.filter ?: return@mapNotNull null
        it.activityInfo?.takeIf { activity -> activity.exported && activity.enabled &&
            filter.hasDataScheme(link.scheme) &&
            filter.countDataAuthorities() == 0 && filter.countDataPaths() == 0 &&
            filter.countDataSchemeSpecificParts() == 0 && filter.countDataTypes() == 0
        }?.packageName
    }.toSet()
    val preferred = pm.resolveActivity(probe, PackageManager.ResolveInfoFlags.of(
        PackageManager.MATCH_DEFAULT_ONLY.toLong(),
    ))?.activityInfo?.packageName
    val browser = preferred?.takeIf { it in handlers } ?: handlers.singleOrNull() ?: return false
    return try {
        context.startActivity(webIntent(Uri.parse(link.url)).setPackage(browser))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun webIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
