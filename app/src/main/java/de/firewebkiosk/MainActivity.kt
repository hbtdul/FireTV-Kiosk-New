package de.firewebkiosk

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
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
    private lateinit var root: FrameLayout

    private val prefs by lazy { getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE) }

    companion object {
        private const val PREF_URL = "last_url"
        private const val DEFAULT_URL = "https://example.com"

        // Rotation in Grad: -90, 0, 90, 180
        private const val PREF_ROTATION_DEG = "rotation_deg"

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

        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        webView = findViewById(R.id.webView)

        // WebView Setup
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

        // gespeicherte Rotation anwenden
        applySavedRotation()

        // URL laden oder beim ersten Start Dialog öffnen
        val saved = prefs.getString(PREF_URL, null)
        if (saved.isNullOrBlank()) {
            showConfigDialog(initial = true)
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

    // -------------------------
    // Options / Config Dialog
    // -------------------------
    private fun showConfigDialog(initial: Boolean) {
        val urlInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(PREF_URL, DEFAULT_URL) ?: DEFAULT_URL)
            setSelection(text.length)
            hint = "https://dein-link.de"
        }

        val rotationLabels = arrayOf("0°", "90°", "180°", "-90°")
        val rotationValues = intArrayOf(0, 90, 180, -90)

        val currentRotation = prefs.getInt(PREF_ROTATION_DEG, 0)
        val checkedIndex = rotationValues.indexOf(currentRotation).let { if (it >= 0) it else 0 }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)

            addView(TextView(this@MainActivity).apply {
                text = "Link (URL)"
                textSize = 16f
            })
            addView(urlInput)

            addView(TextView(this@MainActivity).apply {
                text = "\nMonitor-Rotation"
                textSize = 16f
            })
        }

        AlertDialog.Builder(this)
            .setTitle(if (initial) "Einrichtung" else "Einstellungen")
            .setView(container)
            .setSingleChoiceItems(rotationLabels, checkedIndex, null)
            .setCancelable(!initial)
            .setPositiveButton("OK") { dialog, _ ->
                val urlRaw = urlInput.text?.toString().orEmpty().trim()

                val listView = (dialog as AlertDialog).listView
                val pickedIndex = listView.checkedItemPosition.takeIf { it >= 0 } ?: checkedIndex
                val deg = rotationValues[pickedIndex]

                if (urlRaw.isBlank()) {
                    showConfigDialog(initial)
                    return@setPositiveButton
                }

                val normalized = normalizeUrl(urlRaw)

                prefs.edit()
                    .putString(PREF_URL, normalized)
                    .putInt(PREF_ROTATION_DEG, deg)
                    .apply()

                applyRotation(deg)
                loadUrl(normalized)
            }
            .setNegativeButton(if (initial) "Abbrechen" else "Schließen") { _, _ ->
                if (initial) loadUrl(DEFAULT_URL)
            }
            .setNeutralButton("Updates") { _, _ ->
                checkForUpdate(silentIfNone = false)
            }
            .show()
    }

    // -------------------------
    // Rotation (View-Rotation)
    // -------------------------
    private fun applySavedRotation() {
        val deg = prefs.getInt(PREF_ROTATION_DEG, 0)
        applyRotation(deg)
    }

    private fun applyRotation(deg: Int) {
        // erst nach Layout, damit Maße stimmen
        root.post {
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            val lp = root.layoutParams

            // bei 90/-90: Maße tauschen, dann rotieren => füllt den Screen korrekt
            if (deg == 90 || deg == -90) {
                lp.width = screenH
                lp.height = screenW
            } else {
                lp.width = screenW
                lp.height = screenH
            }

            root.layoutParams = lp
            root.pivotX = lp.width / 2f
            root.pivotY = lp.height / 2f
            root.rotation = deg.toFloat()
        }
    }

    // Fernbedienung:
    // - Zurück = WebView zurück
    // - Menü/Options/Settings/TopMenu/ContextMenu = Config-Dialog
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
            showConfigDialog(initial = false)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    // Fallback: langer Druck auf OK/Select öffnet Dialog (damit es immer erreichbar ist)
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showConfigDialog(initial = false)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
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
                            .setMessage("Konnte nicht prüfen.\nInternet verfügbar?\n\n${e.message ?: ""}")
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
            .setPositiveButton("Installieren") { _, _ -> downloadAndInstallApk() }
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
