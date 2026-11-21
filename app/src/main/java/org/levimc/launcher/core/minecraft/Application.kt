package org.levimc.launcher.core.minecraft

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.levimc.launcher.core.crash.CrashReporter
import org.levimc.launcher.core.discord.DiscordPresence
import org.levimc.launcher.core.packages.PackageService
import org.levimc.launcher.core.news.NewsNotificationHelper
import org.levimc.launcher.settings.FeatureSettings
import org.levimc.launcher.ui.dialogs.LogcatOverlayManager
import java.io.File
import java.util.concurrent.Executors

class LauncherApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        syncDataDirectories(this)

        FeatureSettings.init(applicationContext)
        CrashReporter.init(this)
        val processName = Application.getProcessName()
        if (processName.endsWith(":crash")) return

        PackageService.init(this)
        NewsNotificationHelper.initialize(this)
        LogcatOverlayManager.init(this)

        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        DiscordPresence.initialize(this)
    }

    companion object {
        @JvmStatic
        lateinit var context: Context
            private set

        @JvmStatic
        lateinit var preferences: SharedPreferences
            private set

        private val syncExecutor = Executors.newSingleThreadExecutor()

        @JvmStatic
        fun syncDataDirectories(ctx: Context) {
            val appCtx = ctx.applicationContext
            val internalRoot = appCtx.dataDir // /data/user/0/org.levimc.launcher
            val externalBase = appCtx.getExternalFilesDir(null) ?: return
            val privDataRoot = File(externalBase, "priv_data")

            // 1. Apply any newer files you edited/added in priv_data -> /data/user/0
            if (privDataRoot.exists()) {
                copyNewerFiles(source = privDataRoot, target = internalRoot)
            }

            // 2. Mirror /data/user/0 -> /sdcard/Android/data/org.levimc.launcher/priv_data
            syncExecutor.execute {
                if (!privDataRoot.exists()) privDataRoot.mkdirs()
                copyNewerFiles(source = internalRoot, target = privDataRoot)
            }
        }

        private fun copyNewerFiles(source: File, target: File) {
            val children = source.listFiles() ?: return
            if (!target.exists()) target.mkdirs()

            for (child in children) {
                if (child.name.endsWith(".lock") || child.name == "lib" || child.name == "minecraft_libs") continue

                val dest = File(target, child.name)
                if (child.isDirectory) {
                    copyNewerFiles(child, dest)
                } else {
                    try {
                        if (!dest.exists() || child.lastModified() > dest.lastModified() || child.length() != dest.length()) {
                            child.copyTo(dest, overwrite = true)
                            dest.setLastModified(child.lastModified())
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
