package ir.madreseyar.teacher

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException

class MainActivity : AppCompatActivity() {
    companion object {
        private const val KEY_ALIAS = "madreseyar_teacher_biometric_v1"
        private const val PREFS = "madreseyar_teacher_secure_session"
        private const val PREF_CIPHER = "session_ciphertext"
        private const val PREF_IV = "session_iv"
        private const val PREF_ENABLED = "biometric_enabled"
        private const val MAX_BLOB_BYTES = 40 * 1024 * 1024
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var splash: LinearLayout
    private lateinit var errorPanel: LinearLayout
    private lateinit var filePicker: ActivityResultLauncher<Intent>
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraCaptureUri: Uri? = null
    private var blobPreviewMode = false
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val allowedHost: String by lazy { URI(BuildConfig.WEB_APP_URL).host.lowercase(Locale.ROOT) }
    private val allowedPort: Int by lazy { URI(BuildConfig.WEB_APP_URL).port }
    private val effectiveAllowedPort: Int by lazy { if (allowedPort < 0) 443 else allowedPort }
    private val allowedOrigin: String by lazy {
        val uri = URI(BuildConfig.WEB_APP_URL)
        "${uri.scheme}://${uri.rawAuthority}"
    }
    private var documentStartSupported = false
    private var documentStartScriptHandler: ScriptHandler? = null
    @Volatile private var restoredSessionPayload: String? = null
    @Volatile private var restoredToken: String = ""
    @Volatile private var activeSessionToken: String = ""
    private var promptedTokenFingerprint: String = ""
    private var restoreFailureHandled = false
    private var blobName = "download"
    private var blobMime = "application/octet-stream"
    private var blobBuffer: ByteArrayOutputStream? = null
    private val blobLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.rgb(18, 59, 154)
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= 26) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            if (callback != null) {
                val uris = if (result.resultCode == RESULT_OK) {
                    extractUris(result.data) ?: cameraCaptureUri?.let { arrayOf(it) }
                } else null
                callback.onReceiveValue(uris)
            }
            filePathCallback = null
            cameraCaptureUri = null
        }

        buildLayout()
        configureWebView()

        if (hasEncryptedSession()) {
            authenticateToRestoreSession()
        } else {
            loadWebsite(false)
        }
    }

    private fun buildLayout() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(246, 248, 252)) }
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            setMax(100)
            setProgress(0)
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(21, 94, 239))
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(21, 94, 239))
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP))

        splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(246, 248, 252))
            val logo = ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(logo, LinearLayout.LayoutParams(dp(116), dp(116)))
            val title = TextView(this@MainActivity).apply {
                text = "مدرسه‌یار معلم"
                textSize = 23f
                setTextColor(Color.rgb(18, 59, 154))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(5))
            }
            addView(title)
            val subtitle = TextView(this@MainActivity).apply {
                text = "پنل هوشمند مدیریت کلاس و آموزش"
                textSize = 13f
                setTextColor(Color.rgb(88, 107, 135))
                gravity = Gravity.CENTER
            }
            addView(subtitle)
            val spinner = ProgressBar(this@MainActivity).apply { isIndeterminate = true }
            val lp = LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(22) }
            addView(spinner, lp)
        }
        root.addView(splash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(246, 248, 252))
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply {
                text = "اتصال به سامانه برقرار نشد"
                textSize = 20f
                setTextColor(Color.rgb(24, 47, 82))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = "اتصال امن اینترنت و آدرس سامانه را بررسی کنید؛ سپس دوباره تلاش کنید."
                textSize = 14f
                setTextColor(Color.rgb(90, 108, 133))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(18))
            })
            addView(Button(this@MainActivity).apply {
                text = "تلاش دوباره"
                setTextColor(Color.WHITE)
                setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(21, 94, 239)))
                setOnClickListener { errorPanel.visibility = View.GONE; loadWebsite(restoredSessionPayload != null) }
            })
        }
        root.addView(errorPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun configureWebView() {
        webView.setBackgroundColor(Color.WHITE)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString MadreseyarTeacherAndroid/${BuildConfig.VERSION_NAME}"
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        val messageListenerSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && messageListenerSupported
        if (messageListenerSupported) {
            WebViewCompat.addWebMessageListener(webView, "MSYNative", setOf(allowedOrigin)) { _, message, sourceOrigin, isMainFrame, _ ->
                if (isMainFrame && isAllowedHttpsUri(sourceOrigin)) handleNativeMessage(message.data ?: "")
            }
        }
        if (documentStartSupported) installDocumentStartScript()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return routeUrl(request.url)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = routeUrl(Uri.parse(url))

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progress.visibility = View.VISIBLE
                progress.progress = 8
                errorPanel.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progress.visibility = View.GONE
                splash.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    progress.visibility = View.GONE
                    splash.visibility = View.GONE
                    errorPanel.visibility = View.VISIBLE
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                Toast.makeText(this@MainActivity, "گواهی امنیتی سایت معتبر نیست؛ برای حفاظت از رمز عبور اتصال متوقف شد.", Toast.LENGTH_LONG).show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val accepts = fileChooserParams.acceptTypes
                    .flatMap { it.split(',') }
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotBlank() }
                val acceptsImages = accepts.isEmpty() || accepts.any { it.startsWith("image/") || it == "image/*" }
                val cameraIntent = if (acceptsImages) createCameraCaptureIntent() else null
                val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = choosePickerMimeType(accepts)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                    val mimeTypes = acceptedMimeTypes(accepts)
                    if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                }
                return try {
                    if (fileChooserParams.isCaptureEnabled && cameraIntent != null) {
                        filePicker.launch(cameraIntent)
                    } else {
                        val chooser = Intent.createChooser(pickerIntent, "انتخاب فایل برای بارگذاری")
                        if (cameraIntent != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                        filePicker.launch(chooser)
                    }
                    true
                } catch (_: Exception) {
                    cameraCaptureUri = null
                    this@MainActivity.filePathCallback = null
                    false
                }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("باشه") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("تأیید") { _, _ -> result.confirm() }
                    .setNegativeButton("انصراف") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Camera/microphone access is denied by default. File uploads remain available via the system picker.
                request.deny()
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:", true)) return@DownloadListener
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        })

        if (!documentStartSupported) {
            Toast.makeText(this, "برای قابلیت‌های امن اپ، Android System WebView را به‌روز کنید.", Toast.LENGTH_LONG).show()
        }
    }

    private fun routeUrl(uri: Uri): Boolean {
        val scheme = (uri.scheme ?: "").lowercase(Locale.ROOT)
        val host = (uri.host ?: "").lowercase(Locale.ROOT)
        if (isAllowedHttpsUri(uri)) return false
        if (scheme == "http" && host == allowedHost) {
            Toast.makeText(this, "لینک ناامن HTTP مسدود شد.", Toast.LENGTH_SHORT).show()
            return true
        }
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun isAllowedHttpsUri(uri: Uri): Boolean {
        val scheme = (uri.scheme ?: "").lowercase(Locale.ROOT)
        val host = (uri.host ?: "").lowercase(Locale.ROOT)
        val port = if (uri.port < 0) if (scheme == "https") 443 else 80 else uri.port
        return scheme == "https" && host == allowedHost && port == effectiveAllowedPort
    }

    private fun loadWebsite(restore: Boolean) {
        if (!documentStartSupported && restore) restoredSessionPayload = null
        restoreFailureHandled = false
        splash.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
        val base = BuildConfig.WEB_APP_URL
        val url = if (restore && !base.contains("p635_return=1")) {
            base + (if (base.contains("?")) "&" else "?") + "p635_return=1"
        } else base
        webView.loadUrl(url)
    }

    private fun hasEncryptedSession(): Boolean =
        prefs.getBoolean(PREF_ENABLED, false) && !prefs.getString(PREF_CIPHER, null).isNullOrBlank() && !prefs.getString(PREF_IV, null).isNullOrBlank()

    private fun authenticateToRestoreSession() {
        if (!documentStartSupported) {
            clearBiometricSession()
            loadWebsite(false)
            return
        }
        val can = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            clearBiometricSession()
            Toast.makeText(this, "ورود اثر انگشتی در دسترس نیست؛ با رمز عبور وارد شوید.", Toast.LENGTH_LONG).show()
            loadWebsite(false)
            return
        }
        try {
            val cipher = newCipher()
            val iv = Base64.decode(prefs.getString(PREF_IV, ""), Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(128, iv))
            showBiometricPrompt(
                title = "ورود به پنل معلم",
                subtitle = "برای بازکردن نشست امن مدرسه‌یار، اثر انگشت خود را تأیید کنید.",
                negative = "ورود با رمز عبور",
                cipher = cipher,
                onSuccess = { authenticatedCipher ->
                    try {
                        val encrypted = Base64.decode(prefs.getString(PREF_CIPHER, ""), Base64.NO_WRAP)
                        val plain = authenticatedCipher.doFinal(encrypted).toString(Charsets.UTF_8)
                        val payload = JSONObject(plain)
                        val session = payload.optString("session")
                        val token = payload.optString("token")
                        if (session.isBlank() || token.isBlank()) throw IllegalStateException("نشست ذخیره‌شده ناقص است.")
                        val role = JSONObject(session).optJSONObject("authUser")?.optString("role")
                        if (role != "teacher") throw IllegalStateException("نشست ذخیره‌شده متعلق به معلم نیست.")
                        restoredSessionPayload = plain
                        restoredToken = token
                        activeSessionToken = token
                        installDocumentStartScript()
                        loadWebsite(true)
                    } catch (_: Exception) {
                        clearBiometricSession()
                        Toast.makeText(this, "نشست ذخیره‌شده معتبر نیست؛ دوباره وارد شوید.", Toast.LENGTH_LONG).show()
                        loadWebsite(false)
                    }
                },
                onFallback = {
                    clearBiometricSession()
                    restoredSessionPayload = null
                    loadWebsite(false)
                }
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            clearBiometricSession()
            loadWebsite(false)
        } catch (_: Exception) {
            clearBiometricSession()
            loadWebsite(false)
        }
    }

    private fun showBiometricPrompt(
        title: String,
        subtitle: String,
        negative: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onFallback: () -> Unit
    ) {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val authenticatedCipher = result.cryptoObject?.cipher
                if (authenticatedCipher != null) onSuccess(authenticatedCipher) else onFallback()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_LOCKOUT || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                    onFallback()
                } else if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED) {
                    Toast.makeText(this@MainActivity, "ورود اثر انگشتی لغو شد.", Toast.LENGTH_SHORT).show()
                    onFallback()
                } else {
                    Toast.makeText(this@MainActivity, errString, Toast.LENGTH_LONG).show()
                    onFallback()
                }
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun showEnableBiometricDialog(payload: String, token: String) {
        if (!documentStartSupported) return
        val signature = token.take(18)
        if (signature.isBlank() || promptedTokenFingerprint == signature) return
        promptedTokenFingerprint = signature
        val can = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "برای فعال‌سازی، ابتدا اثر انگشت یا زیست‌سنجی قوی را در تنظیمات گوشی ثبت کنید.", Toast.LENGTH_LONG).show()
            return
        }
        val title = if (hasEncryptedSession()) "به‌روزرسانی ورود اثر انگشتی" else "فعال‌سازی ورود با اثر انگشت"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("پس از اولین ورود با نام کاربری و رمز، می‌توانید دفعات بعد پنل معلم را با اثر انگشت باز کنید. رمز عبور شما ذخیره نمی‌شود؛ فقط نشست ورود به‌صورت رمزنگاری‌شده نگهداری می‌شود.")
            .setPositiveButton("فعال‌سازی") { _, _ -> beginBiometricEnrollment(payload) }
            .setNegativeButton("فعلاً نه", null)
            .show()
    }

    private fun beginBiometricEnrollment(payload: String) {
        try {
            val key = createSecretKey()
            val cipher = newCipher()
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    try {
                        val authenticatedCipher = result.cryptoObject?.cipher ?: throw IllegalStateException("رمزنگاری زیست‌سنجی آماده نیست.")
                        val encrypted = authenticatedCipher.doFinal(payload.toByteArray(Charsets.UTF_8))
                        prefs.edit()
                            .putString(PREF_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                            .putString(PREF_IV, Base64.encodeToString(authenticatedCipher.iv, Base64.NO_WRAP))
                            .putBoolean(PREF_ENABLED, true)
                            .apply()
                        restoredSessionPayload = null
                        restoredToken = JSONObject(payload).optString("token")
                        activeSessionToken = restoredToken
                        installDocumentStartScript()
                        Toast.makeText(this@MainActivity, "ورود با اثر انگشت فعال شد.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        clearBiometricSession()
                        Toast.makeText(this@MainActivity, "ذخیره امن نشست انجام نشد: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(this@MainActivity, "فعال‌سازی اثر انگشت انجام نشد.", Toast.LENGTH_SHORT).show()
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("تأیید برای فعال‌سازی اثر انگشت")
                .setSubtitle("اثر انگشت خود را برای رمزنگاری امن نشست تأیید کنید.")
                .setNegativeButtonText("انصراف")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            Toast.makeText(this, "امکان فعال‌سازی اثر انگشت وجود ندارد: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 24) builder.setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: throw IllegalStateException("کلید امن دستگاه پیدا نشد.")
    }

    private fun newCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    private fun clearBiometricSession() {
        prefs.edit().clear().apply()
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {}
        restoredSessionPayload = null
        restoredToken = ""
        activeSessionToken = ""
        promptedTokenFingerprint = ""
        if (::webView.isInitialized && documentStartSupported) installDocumentStartScript()
    }

    private fun installDocumentStartScript() {
        if (!documentStartSupported) return
        try {
            documentStartScriptHandler?.remove()
            val bridgeScript = assets.open("native-bridge.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val payload = restoredSessionPayload
            val restorePrefix = if (!payload.isNullOrBlank()) {
                val quoted = JSONObject.quote(payload)
                "(function(){try{var p=JSON.parse($quoted);if(p&&p.session&&p.token){Storage.prototype.setItem.call(sessionStorage,'madreseyar.session.v14',p.session);Storage.prototype.setItem.call(sessionStorage,'madreseyar.staff.token.v14',p.token);}}catch(_){}})();\n"
            } else ""
            documentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, restorePrefix + bridgeScript, setOf(allowedOrigin))
        } catch (e: Exception) {
            Toast.makeText(this, "راه‌اندازی پل ارتباطی امن انجام نشد.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleNativeMessage(raw: String) {
        if (raw.isBlank()) return
        val message = try { JSONObject(raw) } catch (_: Exception) { return }
        when (message.optString("type")) {
            "sessionCaptured" -> activeSessionToken = message.optString("token")
            "teacherReady" -> handleTeacherReady(message.optString("session"), message.optString("token"))
            "sessionCleared" -> runOnUiThread { clearBiometricSession() }
            "restoreFailed" -> handleRestoreFailed()
            "roleRejected" -> handleRoleRejected()
            "refreshDashboard" -> runOnUiThread {
                webView.evaluateJavascript("(function(){var u=new URL(location.href);u.searchParams.set('p635_return','1');location.replace(u.toString());})()", null)
            }
            "openUrl" -> runOnUiThread { openNativeUrl(message.optString("url")) }
            "openHtml" -> runOnUiThread { showHtmlPreview(message.optString("html"), false) }
            "printHtml" -> runOnUiThread { showHtmlPreview(message.optString("html"), true) }
            "printPage" -> runOnUiThread {
                try {
                    val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                    printManager.print("گزارش مدرسه‌یار", webView.createPrintDocumentAdapter("Madreseyar"), PrintAttributes.Builder().build())
                } catch (_: Exception) {
                    Toast.makeText(this, "چاپ در این دستگاه در دسترس نیست.", Toast.LENGTH_LONG).show()
                }
            }
            "beginBlobPreview" -> synchronized(blobLock) {
                blobPreviewMode = true
                blobName = message.optString("name", "preview").take(180).ifBlank { "madreseyar-preview" }
                blobMime = message.optString("mime", "application/octet-stream").take(120).ifBlank { "application/octet-stream" }
                blobBuffer = ByteArrayOutputStream()
            }
            "beginBlobDownload" -> synchronized(blobLock) {
                blobPreviewMode = false
                blobName = message.optString("name", "download").take(180).ifBlank { "madreseyar-file" }
                blobMime = message.optString("mime", "application/octet-stream").take(120).ifBlank { "application/octet-stream" }
                blobBuffer = ByteArrayOutputStream()
            }
            "appendBlobDownloadChunk" -> {
                try {
                    val decoded = Base64.decode(message.optString("chunk"), Base64.DEFAULT)
                    synchronized(blobLock) {
                        val buffer = blobBuffer ?: return
                        if (buffer.size() + decoded.size > MAX_BLOB_BYTES) blobBuffer = null else buffer.write(decoded)
                    }
                } catch (_: Exception) {}
            }
            "finishBlobDownload" -> {
                val payload: Pair<Boolean, Triple<String, String, ByteArray>>? = synchronized(blobLock) {
                    val buffer = blobBuffer ?: return
                    val result = Pair(blobPreviewMode, Triple(blobName, blobMime, buffer.toByteArray()))
                    blobBuffer = null
                    blobPreviewMode = false
                    result
                }
                runOnUiThread {
                    payload?.let { (preview, file) ->
                        if (preview) previewBlob(file.first, file.second, file.third)
                        else saveBlobToDownloads(file.first, file.second, file.third)
                    }
                }
            }
        }
    }

    private fun handleTeacherReady(sessionJson: String, token: String) {
        try {
            val user = JSONObject(sessionJson).optJSONObject("authUser")
            if (user?.optString("role") != "teacher" || token.length < 24) {
                handleRoleRejected()
                return
            }
            activeSessionToken = token
            val payload = JSONObject().put("session", sessionJson).put("token", token).toString()
            if (hasEncryptedSession() && restoredToken == token) {
                if (restoredSessionPayload != null) {
                    restoredSessionPayload = null
                    installDocumentStartScript()
                }
                return
            }
            runOnUiThread {
                showEnableBiometricDialog(payload, token)
            }
        } catch (_: Exception) {
            handleRoleRejected()
        }
    }

    private fun handleRestoreFailed() {
        runOnUiThread {
            if (restoreFailureHandled) return@runOnUiThread
            restoreFailureHandled = true
            clearBiometricSession()
            Toast.makeText(this, "نشست قبلی منقضی شده است؛ دوباره با رمز عبور وارد شوید.", Toast.LENGTH_LONG).show()
            webView.evaluateJavascript("sessionStorage.removeItem('madreseyar.session.v14');sessionStorage.removeItem('madreseyar.staff.token.v14');history.replaceState(null,'',location.pathname+location.hash)", null)
        }
    }

    private fun handleRoleRejected() {
        runOnUiThread {
            clearBiometricSession()
            Toast.makeText(this, "این اپلیکیشن مخصوص حساب معلم است.", Toast.LENGTH_LONG).show()
            webView.evaluateJavascript("document.getElementById('logoutBtn')?.click()", null)
        }
    }

    private fun enqueueDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        try {
            val downloadUri = Uri.parse(url)
            if (!isAllowedHttpsUri(downloadUri)) {
                Toast.makeText(this, "دانلود از آدرس ناامن یا خارج از سامانه مجاز نیست.", Toast.LENGTH_LONG).show()
                return
            }
            val request = DownloadManager.Request(downloadUri)
                .setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                .setDescription("دانلود از مدرسه‌یار")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= 24) {
                request.setAllowedOverMetered(true)
                request.setAllowedOverRoaming(false)
            }
            if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
            request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            if (activeSessionToken.isNotBlank()) request.addRequestHeader("Authorization", "Bearer $activeSessionToken")
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType))
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "دانلود فایل آغاز شد.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "دانلود فایل شروع نشد: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveBlobToDownloads(name: String, mime: String, bytes: ByteArray) {
        try {
            val safeName = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "madreseyar-file" }
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Madreseyar")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("ساخت فایل دانلود ممکن نشد.")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("نوشتن فایل دانلود ممکن نشد.")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val file = File(dir, safeName)
                FileOutputStream(file).use { it.write(bytes) }
            }
            Toast.makeText(this, "فایل در پوشه دانلودها ذخیره شد.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ذخیره فایل انجام نشد: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createCameraCaptureIntent(): Intent? {
        return try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("teacher-capture-", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            cameraCaptureUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.takeIf { it.resolveActivity(packageManager) != null }
        } catch (_: Exception) {
            cameraCaptureUri = null
            null
        }
    }

    private fun acceptedMimeTypes(accepts: List<String>): Array<String> {
        if (accepts.isEmpty()) return emptyArray()
        return accepts.mapNotNull {
            when {
                it.startsWith(".") -> when (it) {
                    ".pdf" -> "application/pdf"
                    ".jpg", ".jpeg" -> "image/jpeg"
                    ".png" -> "image/png"
                    ".webp" -> "image/webp"
                    ".csv" -> "text/csv"
                    else -> null
                }
                it.contains("/") -> it
                else -> null
            }
        }.distinct().toTypedArray()
    }

    private fun choosePickerMimeType(accepts: List<String>): String {
        val mimes = acceptedMimeTypes(accepts)
        if (mimes.isEmpty()) return "*/*"
        if (mimes.size == 1) return mimes.first()
        val roots = mimes.map { it.substringBefore('/') }.distinct()
        return if (roots.size == 1) "${roots.first()}/*" else "*/*"
    }

    private fun openNativeUrl(raw: String) {
        if (raw.isBlank() || raw == "about:blank") return
        try {
            val uri = Uri.parse(raw)
            if (isAllowedHttpsUri(uri)) {
                webView.loadUrl(raw)
                return
            }
            if ((uri.scheme ?: "").equals("http", true) && (uri.host ?: "").equals(allowedHost, true)) {
                Toast.makeText(this, "لینک ناامن HTTP مسدود شد.", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "باز کردن لینک ممکن نیست.", Toast.LENGTH_LONG).show()
        }
    }

    private fun configurePreviewWebView(view: WebView) {
        view.setBackgroundColor(Color.WHITE)
        view.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
    }

    private fun showHtmlPreview(html: String, printable: Boolean) {
        if (html.isBlank()) return
        if (html.length > 2_500_000) {
            Toast.makeText(this, "گزارش برای پیش‌نمایش داخل اپ بسیار بزرگ است.", Toast.LENGTH_LONG).show()
            return
        }
        val preview = WebView(this)
        configurePreviewWebView(preview)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (printable) "پیش‌نمایش چاپ / PDF" else "پیش‌نمایش")
            .setView(preview)
            .setNegativeButton("بستن", null)
            .apply { if (printable) setPositiveButton("چاپ / ذخیره PDF", null) }
            .create()
        dialog.setOnDismissListener {
            try { preview.stopLoading(); preview.destroy() } catch (_: Exception) {}
        }
        dialog.setOnShowListener {
            if (printable) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    printWebView(preview, "گزارش مدرسه‌یار")
                }
            }
        }
        dialog.show()
        preview.loadDataWithBaseURL(allowedOrigin + "/", html, "text/html", "UTF-8", null)
    }

    private fun printWebView(view: WebView, name: String) {
        try {
            val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            printManager.print(name, view.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
        } catch (_: Exception) {
            Toast.makeText(this, "چاپ در این دستگاه در دسترس نیست.", Toast.LENGTH_LONG).show()
        }
    }

    private fun previewBlob(name: String, mime: String, bytes: ByteArray) {
        try {
            if (mime.startsWith("image/", true)) {
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val safeMime = mime.takeIf { it.matches(Regex("image/(png|jpeg|jpg|webp|gif)", RegexOption.IGNORE_CASE)) } ?: "image/jpeg"
                showHtmlPreview("<html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><body style=\"margin:0;background:#111;display:flex;align-items:center;justify-content:center;min-height:100vh\"><img alt=\"preview\" style=\"max-width:100%;height:auto\" src=\"data:$safeMime;base64,$encoded\"></body></html>", false)
                return
            }
            val dir = File(cacheDir, "preview").apply { mkdirs() }
            val ext = when {
                mime.contains("pdf", true) -> ".pdf"
                mime.contains("text", true) -> ".txt"
                else -> ""
            }
            val safe = name.replace(Regex("[^A-Za-z0-9._\\-\\u0600-\\u06FF]"), "_").take(80).ifBlank { "madreseyar-preview" }
            val file = File(dir, if (safe.contains('.')) safe else safe + ext)
            FileOutputStream(file).use { it.write(bytes) }
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            saveBlobToDownloads(name, mime, bytes)
            Toast.makeText(this, "نمایشگر مناسب پیدا نشد؛ فایل در Downloads ذخیره شد.", Toast.LENGTH_LONG).show()
        }
    }

    private fun extractUris(data: Intent?): Array<Uri>? {
        if (data == null) return null
        val result = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(result::add)
        }
        if (result.isEmpty()) data.data?.let(result::add)
        return result.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()


    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
            webView.pauseTimers()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeJavascriptInterface("MSYNative")
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
