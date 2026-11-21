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

    private val syncExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        context = applicationContext

        // 1. On launch: If you edited/added files in priv_data, sync them into /data/user/0 first
        // 2. Then mirror the entire /data/user/0 directory out to priv_data so you can inspect everything
        syncDataDirectories()

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

    private fun syncDataDirectories() {
        val internalRoot = dataDir // Points to /data/user/0/org.levimc.launcher (same as /data/data/...)
        val externalBase = getExternalFilesDir(null) ?: return
        val privDataRoot = File(externalBase, "priv_data")

        // Restore any newer user-edited files from priv_data -> /data/user/0 before subsystems init
        if (privDataRoot.exists()) {
            copyNewerFiles(source = privDataRoot, target = internalRoot)
        }

        // Mirror the full /data/user/0 tree -> priv_data in the background
        syncExecutor.execute {
            if (!privDataRoot.exists()) privDataRoot.mkdirs()
            copyNewerFiles(source = internalRoot, target = privDataRoot)
        }
    }

    private fun copyNewerFiles(source: File, target: File) {
        val children = source.listFiles() ?: return
        if (!target.exists()) target.mkdirs()

        for (child in children) {
            // Skip Android lock/socket/cache-lock files that cannot be copied
            if (child.name.endsWith(".lock") || child.name == "lib") continue

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
                    // Ignore transient or locked files
                }
            }
        }
    }

    companion object {
        @JvmStatic
        lateinit var context: Context
            private set

        @JvmStatic
        lateinit var preferences: SharedPreferences
            private set
    }
}
