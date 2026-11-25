package dev.kaspy.herdr

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.annotation.StringRes

internal enum class DictationProvider(val key: String, @StringRes val label: Int) {
    ANDROID("android_on_device", R.string.dictation_provider_android),
    PARAKEETYPE("parakeetype", R.string.dictation_provider_parakeetype);

    fun issue(context: Context): DictationProviderIssue? = when (this) {
        ANDROID -> if (runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false))
            null else DictationProviderIssue.ANDROID_MISSING
        PARAKEETYPE -> checkParakeetype(context.packageManager)
    }

    /** Never call the generic default recognizer, whose implementation may be cloud-based. */
    fun newRecognizer(context: Context): SpeechRecognizer {
        check(issue(context) == null)
        return when (this) {
            ANDROID -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            PARAKEETYPE -> SpeechRecognizer.createSpeechRecognizer(context, parakeetypeComponent())
        }
    }

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: ANDROID
    }
}

internal enum class DictationProviderIssue(@StringRes val message: Int) {
    ANDROID_MISSING(R.string.dictation_provider_android_missing),
    PARAKEETYPE_MISSING(R.string.dictation_provider_parakeetype_missing),
    PARAKEETYPE_UPDATE(R.string.dictation_provider_parakeetype_update),
    PARAKEETYPE_INELIGIBLE(R.string.dictation_provider_parakeetype_ineligible),
}

private const val PARAKEETYPE_PACKAGE = "org.schabi.parakeetype"
private fun parakeetypeComponent() = ComponentName(
    PARAKEETYPE_PACKAGE, "$PARAKEETYPE_PACKAGE.recognition.ParakeetypeRecognitionService",
)

/** Recorded file/pipe support verified in the 0.6.0 release, not just on upstream main. */
internal fun parakeetypeProviderIssue(
    versionName: String?, installed: Boolean, serviceAvailable: Boolean, requestsInternet: Boolean,
): DictationProviderIssue? {
    if (!installed) return DictationProviderIssue.PARAKEETYPE_MISSING
    if (requestsInternet) return DictationProviderIssue.PARAKEETYPE_INELIGIBLE
    val release = Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""")
        .matchEntire(versionName.orEmpty()) ?: return DictationProviderIssue.PARAKEETYPE_UPDATE
    val major = release.groupValues[1].toIntOrNull() ?: return DictationProviderIssue.PARAKEETYPE_UPDATE
    val minor = release.groupValues[2].toIntOrNull() ?: return DictationProviderIssue.PARAKEETYPE_UPDATE
    if (major == 0 && minor < 6) return DictationProviderIssue.PARAKEETYPE_UPDATE
    return if (serviceAvailable) null else DictationProviderIssue.PARAKEETYPE_INELIGIBLE
}

private fun checkParakeetype(pm: PackageManager): DictationProviderIssue? = try {
    val pkg = pm.getPackageInfo(PARAKEETYPE_PACKAGE, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
    val serviceAvailable = try {
        pm.getServiceInfo(parakeetypeComponent(), PackageManager.ComponentInfoFlags.of(0))
            .let { it.exported && it.enabled && it.applicationInfo.enabled }
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
    parakeetypeProviderIssue(
        pkg.versionName, installed = true, serviceAvailable = serviceAvailable,
        requestsInternet = pkg.requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET),
    )
} catch (_: PackageManager.NameNotFoundException) {
    DictationProviderIssue.PARAKEETYPE_MISSING
} catch (_: RuntimeException) {
    DictationProviderIssue.PARAKEETYPE_INELIGIBLE
}
