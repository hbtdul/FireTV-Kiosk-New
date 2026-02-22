package de.firewebkiosk

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.*
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val prefs by lazy { getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE) }

    companion object {
        private const val PREF_URL = "last_url"
        private const val DEFAULT_URL = "https://example.com"

        // mögliche Werte: auto, landscape, portrait, reverse_landscape, reverse_portrait
        private const val PREF_ORIENTATION = "orientation_mode"

        // GitHub latest release API
        private const val GITHUB_LATEST_API =
            "https://api.github.com/repos/hbtdul/FireTV-Kiosk/releases/latest"

        // APK-Download (Asset muss in jedem Release gleich heißen!)
        private const val UPDATE_APK_URL =
            "https://github.com/hbtdul/FireTV-Kiosk/releases/latest/download/firekiosk.apk"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bildschirm wach halten (verhindert Standby, solange App im Vordergrund ist)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Gespeicherte Orientierung anwenden
        applySavedOrientation()

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }
        }

        val saved = prefs.getString(PREF_URL, null)
        if (saved.isNullOrBlank()) {
            askForUrlAndLoad(initial = true)
        } else {
            loadUrl(saved)
        }

        // Auto-Update Check beim Start
        checkForUpdate(silentIfNone = true)
    }

    private fun normalizeUrl(input: String): String {
        val t = input.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        return "https://$t"
    }

    private fun loadUrl(url: String) {
        val normalized = normalizeUrl(url)
        prefs.edit().putString(PREF_URL, normalized).apply()
        webView.loadUrl(normalized)
    }

    private fun askForUrlAndLoad(initial: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(PREF_URL, DEFAULT_URL) ?: DEFAULT_URL)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(if (initial) "URL eingeben" else "URL ändern")
            .setMessage("Welche Webseite soll angezeigt werden?")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val url = input.text?.toString().orEmpty()
                if (url.isBlank()) {
                    askForUrlAndLoad(initial)
                } else {
                    loadUrl(url)
                }
            }
            .setNegativeButton(if (initial) "Abbrechen" else "Zurück") { _, _ ->
                if (initial) loadUrl(DEFAULT_URL)
            }
            .show()
    }

    // Fernbedienung:
    // - Zurück = WebView zurück
    // - Menü/Options/Settings/TopMenu/ContextMenu = Options-Menü
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (::webView.isInitialized && webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }

        // Fire TV Remotes senden je nach Modell unterschiedliche Codes
        if (
            keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_SETTINGS ||
            keyCode == KeyEvent.KEYCODE_MEDIA_TOP_MENU ||
            keyCode == KeyEvent.KEYCODE_MEDIA_CONTEXT_MENU
        ) {
            showOptionsMenu()
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    // Fallback: langer Druck auf OK/Select öffnet Menü (damit es immer erreichbar ist)
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showOptionsMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun showOptionsMenu() {
        val items = arrayOf(
            "URL ändern",
            "Rotation / Ausrichtung",
            "Nach Updates suchen"
        )

        AlertDialog.Builder(this)
            .setCustomTitle(buildMenuHeaderView())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askForUrlAndLoad(initial = false)
                    1 -> showOrientationMenu()
                    2 -> checkForUpdate(silentIfNone = false)
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun buildMenuHeaderView(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 24, 32, 8)
        }

        val logo = ImageView(this).apply {
            // Logo muss unter app/src/main/res/drawable/tendance_logo.png liegen
            setImageResource(R.drawable.tendance_logo)
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { marginEnd = 24 }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val title = TextView(this).apply {
            text = "FireKiosk – Optionen"
            textSize = 20f
            setPadding(0, 30, 0, 0)
        }

        container.addView(logo)
        container.addView(title)
        return container
    }

    private fun showOrientationMenu() {
        val labels = arrayOf(
            "Auto (Standard)",
            "Landscape",
            "Portrait",
            "Reverse Landscape",
            "Reverse Portrait"
        )

        val values = arrayOf(
            "auto",
            "landscape",
            "portrait",
            "reverse_landscape",
            "reverse_portrait"
        )

        val current = prefs.getString(PREF_ORIENTATION, "auto") ?: "auto"
        val checkedIndex = values.indexOf(current).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("Rotation / Ausrichtung")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                prefs.edit().putString(PREF_ORIENTATION, values[which]).apply()
                applySavedOrientation()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun applySavedOrientation() {
        when (prefs.getString(PREF_ORIENTATION, "auto")) {
            "landscape" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "reverse_landscape" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "reverse_portrait" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // -------------------------
    // Auto Update (GitHub latest)
    // -------------------------

    private fun checkForUpdate(silentIfNone: Boolean) {
        Thread {
            try {
                val conn = URL(GITHUB_LATEST_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")

                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonText)

                val tag = json.getString("tag_name") // z.B. "v1.2"
                val remote = tag.removePrefix("v").trim()
                val local = BuildConfig.VERSION_NAME.trim()

                if (isRemoteNewer(remote, local)) {
                    runOnUiThread { showUpdateDialog(tag) }
                } else if (!silentIfNone) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Kein Update")
                            .setMessage("Du hast bereits die neueste Version ($local).")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                if (!silentIfNone) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Update-Check fehlgeschlagen")
                            .setMessage("Konnte nicht prüfen. Internet verfügbar?\n\n${e.message ?: ""}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun isRemoteNewer(remote: String, local: String): Boolean {
        fun parse(v: String): List<Int> =
            v.split(".", "-", "_").mapNotNull { it.toIntOrNull() }

        val r = parse(remote)
        val l = parse(local)
        val n = maxOf(r.size, l.size)

        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    private fun showUpdateDialog(tag: String) {
        AlertDialog.Builder(this)
            .setTitle("Update verfügbar")
            .setMessage("Neue Version verfügbar: $tag\n\nJetzt herunterladen und installieren?")
            .setPositiveButton("Installieren") { _, _ ->
                downloadAndInstallApk()
            }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun downloadAndInstallApk() {
        Thread {
            try {
                val conn = URL(UPDATE_APK_URL).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val outFile = File(cacheDir, "update.apk")
                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                runOnUiThread { promptInstall(outFile) }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Update fehlgeschlagen")
                        .setMessage("Konnte die APK nicht laden.\n\n${e.message ?: ""}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun promptInstall(apkFile: File) {
        // Ab Android O ggf. Erlaubnis zum Installieren unbekannter Apps nötig
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::webView.isInitialized) webView.destroy()
    }
}
