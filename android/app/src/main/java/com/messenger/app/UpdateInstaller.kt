package com.messenger.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.WebView
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивает новую версию APK с сервера и открывает системный установщик.
 * Скачивание идёт в фоне, прогресс и ошибки передаются обратно в страницу через window.__updProgress / __updError.
 */
object UpdateInstaller {

    fun start(act: MainActivity, url: String, web: WebView) {
        val server = BuildConfig.SERVER_URL.trimEnd('/')
        // Качаем только с нашего сервера, чтобы страница не могла подсунуть чужой файл
        if (server.isEmpty() || !url.startsWith("$server/")) {
            report(web, "__updError", "Адрес обновления не совпадает с сервером")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            report(web, "__updError", "Разрешите установку из этого приложения и нажмите «Повторить»")
            return
        }
        Thread {
            try {
                val dir = File(act.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "update.apk")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) throw IOException("сервер ответил ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != lastPercent) {
                                    lastPercent = p
                                    report(web, "__updProgress", p.toString())
                                }
                            }
                        }
                    }
                }
                act.runOnUiThread { launchInstaller(act, file) }
            } catch (e: Exception) {
                report(web, "__updError", "Не удалось скачать обновление: ${e.message}")
            }
        }.start()
    }

    private fun launchInstaller(act: MainActivity, file: File) {
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(intent)
    }

    private fun report(web: WebView, fn: String, arg: String) {
        val quoted = "'" + arg.replace("\\", "\\\\").replace("'", "\\'") + "'"
        web.post { web.evaluateJavascript("window.$fn&&window.$fn($quoted)", null) }
    }
}
