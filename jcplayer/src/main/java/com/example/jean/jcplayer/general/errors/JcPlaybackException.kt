package com.example.jean.jcplayer.general.errors

import com.example.jean.jcplayer.model.JcAudio

/** Source identity survives the service resetting its player after a media error.
 * The message deliberately excludes URLs, which can contain private download tokens.
 */
class JcPlaybackException(val audio: JcAudio?, cause: Exception) :
    Exception("Audio playback failed", cause)
