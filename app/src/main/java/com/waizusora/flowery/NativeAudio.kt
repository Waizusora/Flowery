package com.waizusora.flowery

object NativeAudio {
    init {
        System.loadLibrary("flowery_audio")
    }

    external fun nativeEngineVersion(): String
}
