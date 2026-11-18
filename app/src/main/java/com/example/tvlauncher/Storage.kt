package com.example.tvlauncher

import android.content.Context
import android.os.Environment
import java.io.File

/** Finds the TV's internal storage and every attached USB drive / SD card. */
object Storage {
    class Vol(val root: File, val removable: Boolean)

    fun volumes(ctx: Context): List<Vol> {
        val out = LinkedHashMap<String, Vol>()
        fun add(f: File?, removable: Boolean) {
            try {
                if (f != null && f.isDirectory && f.canRead()) {
                    val key = f.canonicalPath
                    if (!out.containsKey(key)) out[key] = Vol(f, removable)
                }
            } catch (e: Exception) {
            }
        }
        add(Environment.getExternalStorageDirectory(), false)
        try {
            for (d in ctx.getExternalFilesDirs(null)) {
                if (d == null) continue
                val p = d.absolutePath
                val i = p.indexOf("/Android/")
                if (i > 0) add(File(p.substring(0, i)), !p.startsWith("/storage/emulated"))
            }
        } catch (e: Exception) {
        }
        try {
            File("/storage").listFiles()?.forEach { f ->
                val n = f.name
                if (n != "self" && n != "emulated" && !n.startsWith("sdcard")) add(f, true)
            }
            File("/mnt").listFiles()?.forEach { f ->
                val n = f.name.lowercase()
                if (n.startsWith("usb") || n.startsWith("udisk") || n.startsWith("sd") && n != "sdcard") add(f, true)
            }
        } catch (e: Exception) {
        }
        try {
            File("/proc/mounts").forEachLine { line ->
                val p = line.split(" ")
                if (p.size >= 3) {
                    val mp = p[1]
                    val fs = p[2]
                    val usbFs = fs == "vfat" || fs == "exfat" || fs == "ntfs" || fs == "fuseblk" || fs == "texfat" || fs == "sdfat"
                    if (usbFs && (mp.startsWith("/storage/") || mp.startsWith("/mnt/")) && !mp.contains("emulated")) add(File(mp), true)
                }
            }
        } catch (e: Exception) {
        }
        return out.values.toList()
    }
}
