package com.example.tvlauncher

import android.media.MediaCodecList

object DecoderInfo {
    /** true = a hardware decoder exists for this mime type, false = only software, null = unknown. */
    fun hasHardware(mime: String?): Boolean? {
        if (mime == null) return null
        return try {
            var found = false
            var hw = false
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                if (info.supportedTypes.any { it.equals(mime, true) }) {
                    found = true
                    if (!isSoftwareName(info.name)) hw = true
                }
            }
            if (found) hw else null
        } catch (e: Exception) {
            null
        }
    }

    fun isSoftwareName(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.contains("ffmpeg")
    }

    /** "hardware", "software (system)" or "software (FFmpeg)" for a decoder name reported by the player. */
    fun describe(name: String?): String? {
        if (name == null) return null
        val n = name.lowercase()
        return when {
            n.contains("ffmpeg") -> "SW FFmpeg"
            isSoftwareName(name) -> "SW system"
            else -> "HW"
        }
    }

    fun shortName(mime: String?): String? = when (mime) {
        null -> null
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/mpeg2" -> "MPEG-2"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/dolby-vision" -> "Dolby Vision"
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "AC3"
        "audio/eac3" -> "E-AC3"
        "audio/eac3-joc" -> "Atmos"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/true-hd" -> "TrueHD"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        else -> mime.substringAfter('/')
    }
}
