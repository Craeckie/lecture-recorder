package org.Craeckie.lecturerecorder

import android.annotation.SuppressLint
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.Craeckie.lecturerecorder.ui.theme.AppTheme

// The site this app wraps. See CLAUDE.md for everything worth knowing about wrapping a
// third-party site in a WebView (dark mode, injection timing, sizing pitfalls, ...).
private const val SITE_URL = "https://lt2srv.iar.kit.edu/"

// Logcat tag for the forwarded page console (adb logcat -s LectureRecorder).
internal const val LOG_TAG = "LectureRecorder"

// Dark theme via the real Dark Reader engine (bundled MIT-licensed library, see
// app/src/main/assets/darkreader.js + darkreader-LICENSE.txt), not a CSS filter hack.
// Dark Reader's Dynamic Theme analyzes each element's actual colors and computes a
// matching dark replacement per element, which is why saturated content comes out looking
// natural instead of hue-shifted the way a blanket filter:invert()+hue-rotate(180deg) does.
//
// Limits to know about (details in CLAUDE.md):
//  - Cross-origin images (map tiles, icon PNGs) can't be recolored — their pixels are
//    unreadable to it. Dark Reader leaves them as-is; if they must be dark, apply a CSS
//    filter to their container instead.
//  - If the site has its own dark theme, prefer toggling that and disabling Dark Reader
//    while it's active (see the wetter project for a body-class-observer example).
//
// Enabling is idempotent (safe from both onPageStarted and onPageFinished), and the
// "if (!window.DarkReader)" guard around the bundle keeps the ~346KB from being
// re-parsed twice within the same document.
private val ENABLE_DARKREADER_JS = """
    (function () {
        if (!window.DarkReader) { return; }
        // The standalone bundle can't use the browser extension's privileged
        // cross-origin fetch for stylesheets; routing through the page's own fetch
        // at least covers same-origin sheets (this page's own CSS).
        DarkReader.setFetchMethod(window.fetch);
        if (!DarkReader.isEnabled()) {
            DarkReader.enable({ brightness: 100, contrast: 100, sepia: 0 });
        }
    })();
""".trimIndent()

private fun injectDarkReaderJs(bundle: String): String = """
    (function() {
        if (!window.DarkReader) {
            $bundle
        }
    })();
""".trimIndent()

// Per-site tweaks injected on every load: hide elements, kill consent overlays, pin
// layout. Everything must be IDEMPOTENT (guarded by window.__appTweaks) because it is
// injected from several places — see the injection-timing notes in CLAUDE.md.
//
// The MutationObserver on <body> catches overlays that are inserted asynchronously
// after load (consent dialogs, cookie banners, promo popups).
private val SITE_TWEAKS_JS = """
    (function () {
        function killOverlays() {
            // Add the site's overlay/banner selectors here, e.g.:
            // document.querySelectorAll('.fc-consent-root, .cc-window')
            //     .forEach(function (e) { e.remove(); });
        }
        function setup() {
            if (window.__appTweaks || !document.body) return;
            window.__appTweaks = true;
            console.log('[app-tweaks] armed');
            var st = document.createElement('style');
            // Site-specific hide rules (display:none !important keeps hiding elements
            // that are re-inserted dynamically), e.g.:
            // st.textContent = '.some-ad-slot { display: none !important; }';
            st.textContent = '';
            document.head.appendChild(st);
            new MutationObserver(killOverlays).observe(document.body, { childList: true });
        }
        killOverlays();
        // Arm on DOMContentLoaded rather than relying on a later injection: the WebView's
        // onPageFinished tracks the window 'load' event, which ad/consent scripts can
        // stall for a long time on a real device. The site's own scripts typically run
        // at DOMContentLoaded — ours should too.
        if (document.body) { setup(); }
        else { document.addEventListener('DOMContentLoaded', setup); }
    })();
""".trimIndent()

// Shown instead of the WebView's stock "Webpage not available" page when the main frame
// fails to load (no network, server down, timeout). Auto-retries via meta refresh every
// 8 seconds and immediately on tap; matches the app's day/night background so the error
// state doesn't flash a mismatched screen.
private fun errorPageHtml(isDark: Boolean, reason: String): String {
    val bg = if (isDark) "#111111" else "#fafafa"
    val fg = if (isDark) "#9e9e9e" else "#555555"
    val host = SITE_URL.removePrefix("https://").removePrefix("http://").trimEnd('/')
    return """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <meta http-equiv="refresh" content="8;url=$SITE_URL">
        <style>
            body { background: $bg; color: $fg; font-family: sans-serif; margin: 0;
                   height: 100vh; display: flex; align-items: center; justify-content: center; }
            a { color: inherit; text-align: center; text-decoration: none; padding: 2em; }
            small { opacity: .6 }
        </style>
        </head><body>
        <a href="$SITE_URL">$host is unreachable<br>
        <small>$reason &middot; retrying automatically, tap to retry now</small></a>
        </body></html>
    """.trimIndent()
}

// Whether the system is currently in night mode, read live off the WebView's context so
// it reflects the setting at the time of each page load (the activity restarts on a
// system theme change, since uiMode isn't declared in android:configChanges).
private fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

class MainActivity : ComponentActivity() {
    private lateinit var micRouter: MicRouter
    private lateinit var micDiagnostics: MicDiagnostics

    // Held while the OS permission dialog is up, so the page's own permission request can
    // be answered once the user has decided. Null at all other times.
    private var pendingWebPermission: PermissionRequest? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(LOG_TAG, "RECORD_AUDIO granted=$granted")
            val pending = pendingWebPermission
            pendingWebPermission = null
            if (pending == null) return@registerForActivityResult
            if (granted) {
                pending.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                pending.deny()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Diagnostics first, so the device inventory is logged before any routing decision.
        micDiagnostics = MicDiagnostics(this)
        micDiagnostics.attach()
        micRouter = MicRouter(this)
        micRouter.attach()
        // Asked before the page loads, so the OS dialog doesn't land on top of the site's
        // own recording UI mid-lecture.
        if (!hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent {
            AppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SiteWebView(
                        modifier = Modifier.padding(innerPadding),
                        onAudioPermissionRequest = ::handleWebAudioPermission,
                    )
                }
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // The page's getUserMedia lands here. Grant audio capture only — and only when the OS
    // permission is actually held, since granting a WebView resource the app doesn't own
    // just makes the capture fail later with a less obvious error.
    private fun handleWebAudioPermission(request: PermissionRequest) {
        if (!request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            Log.i(LOG_TAG, "Denying page permission request for ${request.resources.joinToString()}")
            request.deny()
            return
        }
        if (hasMicPermission()) {
            Log.i(LOG_TAG, "Granting page audio capture")
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            // A second request while the OS dialog for the first is still up would
            // otherwise silently orphan the first PermissionRequest — never granted or
            // denied, leaving the page's getUserMedia promise hanging forever.
            pendingWebPermission?.let {
                Log.i(LOG_TAG, "Displaced pending audio permission request by a newer one; denying it")
                it.deny()
            }
            pendingWebPermission = request
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Deliberately onDestroy and not onPause: the routing has to survive the screen going
    // off while a lecture is being recorded.
    override fun onDestroy() {
        micRouter.detach()
        micDiagnostics.detach()
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteWebView(
    modifier: Modifier = Modifier,
    onAudioPermissionRequest: (PermissionRequest) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    // canGoBack() is a plain method call, not Compose state, so it must be mirrored
    // into a State explicitly (updated on every navigation) for BackHandler to react
    // to in-page navigation instead of latching to the value from first composition.
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                // CRITICAL: Compose's AndroidView holder adds the factory view without
                // LayoutParams, so it gets the ViewGroup default WRAP_CONTENT — and a
                // WebView whose layoutParams.height is WRAP_CONTENT switches Chromium
                // into grow-with-content mode, where CSS percentage heights resolve
                // against ZERO. Any site that sizes itself with height:100% chains
                // collapses to its min-height floors (observed on-device: html at 0px
                // rendered height while window.innerHeight reported the full viewport).
                // No JS/CSS fix exists for this failure mode — 100vh "works" but gets
                // clipped by the still-collapsed overflow:hidden ancestors.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Lets the live page be inspected via chrome://inspect#devices on a
                // connected computer, e.g. to diagnose page-injection issues.
                val isDebuggable =
                    context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
                if (isDebuggable) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                // Page console → logcat, so injection state is visible via
                // `adb logcat -s <LOG_TAG>` even without a chrome://inspect session.
                // Return false instead if other tooling parses chromium's own
                // "[INFO:CONSOLE]" logcat lines (returning true suppresses them).
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        Log.d(LOG_TAG, "${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                        return true
                    }

                    // Without this override WebView denies every getUserMedia call, so the
                    // site's recorder silently never starts.
                    override fun onPermissionRequest(request: PermissionRequest) {
                        onAudioPermissionRequest(request)
                    }
                }
                val isDark = isNightMode(context)
                // Loaded once per WebView instance rather than per navigation.
                val darkReaderInjectJs = if (isDark) {
                    val bundle = context.assets.open("darkreader.js").bufferedReader().use { it.readText() }
                    injectDarkReaderJs(bundle)
                } else {
                    null
                }
                if (isDark) {
                    // Avoids a white flash of the WebView's own surface before the page
                    // has painted and Dark Reader has kicked in.
                    setBackgroundColor(Color.parseColor("#111111"))
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url
                        if (url.scheme == "http" || url.scheme == "https") return false
                        // Non-http(s) links (mailto:, tel:, intent:, ...) can't be
                        // loaded by the WebView itself; hand them to the system.
                        return try {
                            view.context.startActivity(Intent(Intent.ACTION_VIEW, url))
                            true
                        } catch (_: ActivityNotFoundException) {
                            true
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        super.onReceivedError(view, request, error)
                        // Subresources (ads, trackers, tiles) fail all the time — only a
                        // failed main document warrants the error screen.
                        if (!request.isForMainFrame) return
                        view.loadDataWithBaseURL(
                            null,
                            errorPageHtml(isNightMode(view.context), error.description.toString()),
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (darkReaderInjectJs != null) {
                            view.evaluateJavascript(darkReaderInjectJs, null)
                            view.evaluateJavascript(ENABLE_DARKREADER_JS, null)
                        }
                        // Inject early and re-post on timers (scheduled here, NOT in
                        // onPageFinished — the 'load' event can be stalled indefinitely
                        // by ad/consent scripts) in case this very first injection lands
                        // before the document exists at all. The script itself arms on
                        // DOMContentLoaded and is idempotent, so double-injection is safe.
                        view.evaluateJavascript(SITE_TWEAKS_JS, null)
                        for (delayMs in longArrayOf(2000, 10000)) {
                            view.postDelayed({ view.evaluateJavascript(SITE_TWEAKS_JS, null) }, delayMs)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (darkReaderInjectJs != null) {
                            // Re-asserted after load in case late page scripts touched
                            // <head> after our first injection. The "if (!window.DarkReader)"
                            // guard keeps this from re-parsing the ~346KB bundle a second
                            // time within the same document.
                            view.evaluateJavascript(darkReaderInjectJs, null)
                            view.evaluateJavascript(ENABLE_DARKREADER_JS, null)
                        }
                        view.evaluateJavascript(SITE_TWEAKS_JS, null)
                        canGoBack = view.canGoBack()
                    }
                }
                loadUrl(SITE_URL)
                webView = this
            }
        },
        onRelease = {
            webView?.destroy()
            webView = null
        },
    )
}
