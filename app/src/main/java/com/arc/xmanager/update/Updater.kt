package com.arc.xmanager.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
)

/** Messages remontés par l'installateur système vers l'interface. */
object UpdateBus {
    val message = MutableStateFlow<String?>(null)
}

/**
 * Mises à jour depuis les Releases GitHub du dépôt (tag "v<versionCode>", asset .apk).
 * Installation via PackageInstaller : à partir d'Android 12, une fois que l'app s'est mise à jour
 * elle-même une première fois, les mises à jour suivantes peuvent se faire sans confirmation.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/Jeenius1121/X/releases/latest"

    fun currentVersion(ctx: Context): Pair<Long, String> {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(pi) to (pi.versionName ?: "?")
    }

    /** Retourne la mise à jour disponible, ou null si l'app est à jour. Bloquant. */
    @Throws(IOException::class)
    fun check(ctx: Context): UpdateInfo? {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code == 404) return null // aucune release publiée
            if (code !in 200..299) throw IOException("GitHub a répondu $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val remote = json.optString("tag_name").filter(Char::isDigit).toLongOrNull() ?: return null
            if (remote <= currentVersion(ctx).first) return null
            val assets = json.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            return UpdateInfo(
                versionCode = remote,
                versionName = json.optString("name").ifEmpty { json.optString("tag_name") },
                notes = json.optString("body").trim(),
                apkUrl = url ?: return null,
            )
        } finally {
            conn.disconnect()
        }
    }

    @Throws(IOException::class)
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "update-${info.versionCode}.apk")
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) throw IOException("Téléchargement : HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** L'utilisateur doit autoriser X Manager à installer des applis (une seule fois). */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags
            )
            session.commit(pending.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // l'app redémarre sur la nouvelle version
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateBus.message.value = "Mise à jour annulée."
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateBus.message.value = "Cette version est signée avec une autre clé : désinstalle " +
                    "X Manager puis installe la nouvelle version (une seule fois)."
            else -> UpdateBus.message.value = "Échec de la mise à jour ($status) : " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erreur inconnue")
        }
    }
}
