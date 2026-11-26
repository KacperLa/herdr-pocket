package dev.kaspy.herdr

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Owns only a speaker selection explicitly requested for this Live Voice call. */
internal class LiveVoiceSpeakerRoute(private val output:Output) {
    internal interface Output {
        fun selectSpeaker():Boolean
        fun releaseSpeaker()
    }

    var selected=false
        private set

    fun setEnabled(enabled:Boolean):Boolean {
        if(enabled==selected) return true
        if(enabled) {
            if(!output.selectSpeaker()) return false
            selected=true
            return true
        }
        return release()
    }

    // Also try after a failed request; its AudioManager setup may only partly have succeeded.
    fun endCall() { release() }

    private fun release():Boolean {
        val released=runCatching { output.releaseSpeaker() }.isSuccess
        if(released) selected=false
        return released
    }
}

/** Uses the communication route for both the voice track and its corresponding mic. */
internal class AndroidLiveVoiceSpeakerOutput(private val audio:AudioManager):LiveVoiceSpeakerRoute.Output {
    private var claimsMode=false
    private var ownsSelection=false

    override fun selectSpeaker():Boolean {
        return runCatching {
            val speaker=audio.availableCommunicationDevices.firstOrNull {
                it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.isSink
            } ?: return false
            when(audio.mode) {
                AudioManager.MODE_NORMAL -> {
                    audio.mode=AudioManager.MODE_IN_COMMUNICATION
                    claimsMode=true
                }
                AudioManager.MODE_IN_COMMUNICATION -> Unit
                // Don't try to route a cellular call or ringing through this app.
                else -> return false
            }
            ownsSelection=audio.setCommunicationDevice(speaker)
            ownsSelection
        }.getOrDefault(false).also {
            if(!it) runCatching(::releaseSpeaker)
        }
    }

    override fun releaseSpeaker() {
        try {
            if(ownsSelection) {
                audio.clearCommunicationDevice()
                ownsSelection=false
            }
        } finally {
            if(claimsMode) {
                // MODE_NORMAL releases this process's mode request, not another app's call.
                audio.mode=AudioManager.MODE_NORMAL
                claimsMode=false
            }
        }
    }
}
