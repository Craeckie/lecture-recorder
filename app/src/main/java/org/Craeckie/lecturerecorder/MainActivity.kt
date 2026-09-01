package org.Craeckie.lecturerecorder

import android.annotation.SuppressLint
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.Craeckie.lecturerecorder.ui.theme.AppTheme

// The site this app wraps. See CLAUDE.md for everything worth knowing about wrapping a
// third-party site in a WebView (dark mode, injection timing, sizing pitfalls, ...).
private const val SITE_URL = "https://lt2srv.iar.kit.edu/"

// Logcat tag for the forwarded page console (adb logcat -s LectureRecorder).
internal const val LOG_TAG = "LectureRecorder"

// Dark theme via the real Dark Reader engine (bundled as
// app/src/main/assets/darkreader.js), not a CSS filter hack.
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

// The other half of the switch: the system theme can change mid-recording, and the
// activity no longer restarts when it does (uiMode is in android:configChanges, because a
// restart would reload the page and delete the recording session). So a page that was
// loaded in night mode has to be un-darkened in place. Idempotent, and safe on a page
// where the bundle was never injected at all.
private val DISABLE_DARKREADER_JS = """
    (function () {
        if (window.DarkReader && DarkReader.isEnabled()) { DarkReader.disable(); }
    })();
""".trimIndent()

private fun injectDarkReaderJs(bundle: String): String = """
    (function() {
        if (!window.DarkReader) {
            $bundle
        }
    })();
""".trimIndent()

// The ~346KB bundle, read from assets at most once per process. Day/night is now a live
// switch rather than a per-WebView constant, so this can be asked for at any moment --
// including in the middle of a lecture, where hitting the disk again would be a pointless
// main-thread stall. Main-thread only, hence no synchronization.
private var darkReaderBundle: String? = null

private fun darkReaderInjectJs(context: Context): String {
    val bundle = darkReaderBundle
        ?: context.assets.open("darkreader.js").bufferedReader().use { it.readText() }
            .also { darkReaderBundle = it }
    return injectDarkReaderJs(bundle)
}

// The WebView's own surface color, so there is no white flash before the page paints.
private const val DARK_SURFACE = "#111111"
private const val LIGHT_SURFACE = "#FFFFFF"

// A long digit/hex path SEGMENT is the wrapped site's session id. The injected JS has its
// own copies of this rule (redactPath in SITE_TWEAKS_JS, redactIds in NET_TRACE_JS and
// PERF_TRACE_JS) covering the lines those probes emit; this is the Kotlin one, covering
// the forwarded page console, which was the one uncovered path — capture A logged the id
// in the clear 52 times through it.
private val SESSION_ID_SEGMENT = Regex("/[0-9a-fA-F]{20,}")

// Redacts an id segment out of arbitrary free-form text (msg.message()) -- the wrapped
// site's own console.log calls, which are NOT necessarily a URL and can legitimately
// contain a literal '?' or '#' anywhere in the string. Deliberately does NOT strip a
// query/fragment: doing that here once silently truncated unrelated trailing content
// with no marker (e.g. "processed 5 items, query was ?x=1, continuing" lost everything
// from the '?' on). The id itself is still caught: SESSION_ID_SEGMENT anchors on '/' plus
// 20+ hex chars, and per the spec's own reasoning the session id lives in the path, not
// the query string, so skipping the query strip loses no redaction coverage.
internal fun redactSessionIds(text: String?): String {
    val raw = text ?: return ""
    return SESSION_ID_SEGMENT.replace(raw, "/<id>")
}

// Redacts msg.sourceId() -- an actual URL, where a query string can genuinely appear and
// is safe to discard outright (unlike the free-form case above). Strips query/fragment for
// defence-in-depth, matching the JS-side redactors this was modelled on (redactPath in
// SITE_TWEAKS_JS, redactIds in NET_TRACE_JS/PERF_TRACE_JS), which all parse
// `new URL(...).pathname` first for the same reason. Then delegates to redactSessionIds
// for the id-segment redaction itself.
internal fun redactSourceUrl(url: String?): String {
    val raw = url ?: return ""
    val withoutQuery = raw.substringBefore('?').substringBefore('#')
    return redactSessionIds(withoutQuery)
}

// Per-site tweaks injected on every load: hide elements, kill consent overlays, pin
// layout. Everything must be IDEMPOTENT (guarded by window.__appTweaks) because it is
// injected from several places — see the injection-timing notes in CLAUDE.md.
//
// The MutationObserver on <body> catches overlays that are inserted asynchronously
// after load (consent dialogs, cookie banners, promo popups).
// Network tracing for debug builds: wraps fetch and WebSocket and reports through the page
// console, which MainActivity already forwards to logcat. This exists because the usual
// answer -- chrome://inspect#devices -- needs a desktop Chrome that is not always available,
// and on this app's slow mobile link (see CLAUDE.md, "Operating environment") the network
// layer is exactly where the interesting failures are.
//
// Deliberately compact: per-request lines only for failures, non-2xx and slow requests, plus
// a rolled-up line per endpoint every 5s. A continuous audio upload would otherwise produce
// hundreds of lines a minute and drown the rest of the log.
//
// Logs metadata only -- method, path, status, duration, byte counts. Never headers, never
// bodies, so nothing here can leak a session token into a log file you send onward.
private val NET_TRACE_JS = """
    (function () {
        if (window.__appNetTrace) return;
        window.__appNetTrace = true;
        var TAG = '[net]';
        var SUMMARY_MS = 5000;
        var SLOW_MS = 2000;
        var buckets = {};
        var wsCount = 0;

        function bucket(key) {
            if (!buckets[key]) buckets[key] = { n: 0, err: 0, up: 0, down: 0, ms: [] };
            return buckets[key];
        }
        // The session id lives in the PATH (e.g. /webapi/123456789012345678901234567890123456789/0/append),
        // not the query string -- so stripping query strings alone does not keep it out of a
        // log. Replace any long digit/hex path segment with a placeholder instead.
        function redactIds(p) {
            return p.replace(/\/[0-9a-f]{20,}/gi, '/<id>');
        }
        function pathOf(url) {
            try { return redactIds(new URL(url, location.href).pathname); }
            catch (e) { return redactIds(String(url)); }
        }
        // Approximate: string length is characters, not bytes. Good enough to spot an
        // upload that is far bigger or smaller than expected.
        function bodySize(body) {
            try {
                if (!body) return 0;
                if (typeof body === 'string') return body.length;
                if (body.byteLength != null) return body.byteLength;
                if (body.size != null) return body.size;
            } catch (e) {}
            return 0;
        }
        function quantile(sorted, q) {
            if (!sorted.length) return 0;
            return Math.round(sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * q))]);
        }

        // Response BODIES for a small allow-list of paths, so tools/mock-server.mjs can be
        // corrected against what the server really returns instead of what the page's code
        // implies. Everything else in this tracer is metadata only, deliberately: a logcat
        // export must not carry transcript text or a session token.
        //
        // Which is why this list holds ONLY /append, whose response is an upload ack.
        // /get_previous_messages and /webapi/stream carry lecture content -- do not add
        // them without deciding that is acceptable for the log you are about to export.
        var BODY_PATHS = ['/append'];
        var BODY_MAX = 400;
        var BODY_SAMPLES = 3;
        var bodySeen = {};
        function maybeLogBody(key, path, res) {
            if (!BODY_PATHS.some(function (s) { return path.indexOf(s) >= 0; })) return;
            bodySeen[path] = (bodySeen[path] || 0) + 1;
            // A lecture is an hour of these. Three samples is enough to see whether the
            // shape varies between requests, and bounds what this can add to a log.
            if (bodySeen[path] > BODY_SAMPLES) return;
            var n = bodySeen[path];
            try {
                // clone() so the page still gets to read its own response body.
                res.clone().text().then(function (t) {
                    console.log(TAG + ' BODY ' + key + ' ' + res.status
                        + ' [' + n + '/' + BODY_SAMPLES + '] '
                        + JSON.stringify(String(t).slice(0, BODY_MAX)));
                }, function (e) {
                    console.log(TAG + ' BODY ' + key + ' unreadable: ' + e);
                });
            } catch (e) {}
        }

        var origFetch = window.fetch;
        if (origFetch) {
            window.fetch = function (input, init) {
                var method = (init && init.method) || (input && input.method) || 'GET';
                var path = pathOf((input && input.url) || input);
                var key = method + ' ' + path;
                var up = bodySize(init && init.body);
                var b = bucket(key);
                var t0 = performance.now();
                return origFetch.apply(this, arguments).then(function (res) {
                    var dt = performance.now() - t0;
                    b.n++; b.up += up; b.ms.push(dt);
                    var len = 0;
                    try { len = parseInt(res.headers.get('content-length'), 10) || 0; } catch (e) {}
                    b.down += len;
                    maybeLogBody(key, path, res);
                    if (!res.ok) {
                        console.log(TAG + ' HTTP ' + res.status + ' ' + key + ' ' + Math.round(dt) + 'ms');
                    } else if (dt >= SLOW_MS) {
                        console.log(TAG + ' SLOW ' + key + ' ' + Math.round(dt) + 'ms up=' + up + 'B');
                    }
                    return res;
                }, function (err) {
                    var dt = performance.now() - t0;
                    b.n++; b.err++; b.ms.push(dt);
                    // The line that distinguishes a real transport failure from a fetch
                    // aborted by navigation: a navigation abort lands within a millisecond
                    // or two of a visibilitychange, a dead link does not.
                    console.log(TAG + ' FAIL ' + key + ' after ' + Math.round(dt) + 'ms: ' + err);
                    throw err;
                });
            };
        }

        var OrigWS = window.WebSocket;
        if (OrigWS) {
            var PatchedWS = function (url, protocols) {
                var ws = (protocols === undefined) ? new OrigWS(url) : new OrigWS(url, protocols);
                var id = ++wsCount;
                var path = pathOf(url);
                var b = bucket('WS ' + path);
                var t0 = performance.now();
                console.log(TAG + ' WS#' + id + ' connecting ' + path);
                ws.addEventListener('open', function () {
                    console.log(TAG + ' WS#' + id + ' open after ' + Math.round(performance.now() - t0) + 'ms');
                });
                ws.addEventListener('message', function (ev) {
                    b.n++;
                    var d = ev.data;
                    b.down += (typeof d === 'string') ? d.length : ((d && (d.byteLength || d.size)) || 0);
                });
                ws.addEventListener('error', function () {
                    b.err++;
                    console.log(TAG + ' WS#' + id + ' ERROR');
                });
                ws.addEventListener('close', function (ev) {
                    console.log(TAG + ' WS#' + id + ' closed code=' + ev.code + ' clean=' + ev.wasClean);
                });
                return ws;
            };
            PatchedWS.prototype = OrigWS.prototype;
            ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (k) { PatchedWS[k] = OrigWS[k]; });
            window.WebSocket = PatchedWS;
        }

        setInterval(function () {
            Object.keys(buckets).forEach(function (key) {
                var b = buckets[key];
                if (!b.n) return;
                var sorted = b.ms.slice().sort(function (x, y) { return x - y; });
                console.log(TAG + ' ' + key + ' n=' + b.n + ' err=' + b.err
                    + (sorted.length ? ' p50=' + quantile(sorted, 0.5) + 'ms max=' + quantile(sorted, 1) + 'ms' : '')
                    + ' up=' + Math.round(b.up / 1024) + 'KB down=' + Math.round(b.down / 1024) + 'KB');
                buckets[key] = { n: 0, err: 0, up: 0, down: 0, ms: [] };
            });
        }, SUMMARY_MS);

        console.log(TAG + ' tracing armed');
    })();
""".trimIndent()
// Audio-pipeline and main-thread tracing for debug builds.
//
// NET_TRACE_JS answers "is anything going over the wire". This answers the question that
// comes first: does the page's capture pipeline ever produce anything to send? The page
// reports nothing when that pipeline breaks -- no error, no banner, just an empty
// transcript -- so every stage has to be observed from outside.
//
// The pipeline, in order (site-reference/present/inline/05-audio-capture-and-upload.js):
//
//   getUserMedia -> AudioContext -> ScriptProcessorNode.onaudioprocess
//     -> waveResampler.resample(48k -> 16k)      <- loaded from cdn.jsdelivr.net
//     -> new AudioData(...) -> AudioEncoder.encode()   <- WebCodecs Opus
//     -> opusPackets[] -> POST /append
//
// Each stage below has its own counter, because each fails differently and silently:
//
//   ctx=        AudioContext state. 'suspended' means the autoplay/gesture policy never
//               let it start -- the site calls recording() at load, with no user gesture
//               anywhere in the stack, and never calls resume(). Nothing downstream runs.
//   cb=         ScriptProcessorNode callbacks. 0 while ctx=running means the node was
//               never wired up; a gap far above 'expect' means the main thread starved it.
//   cbErr=      exceptions thrown inside the site's own callback. The site does not catch
//               them, so one per callback silences capture completely while leaving the
//               UI looking healthy. The likely thrower is waveResampler being undefined:
//               it is a render-blocking script from a third-party CDN, so a browser that
//               cannot reach cdn.jsdelivr.net loses capture entirely while a browser with
//               it cached keeps working. resampler= reports whether it is present.
//   enc=        AudioEncoder.encode() calls vs. packets its output callback produced.
//               encode>0 with pkt=0 means WebCodecs accepted the audio and emitted
//               nothing -- the encoder is the failing stage.
//   path=       which field the page actually POSTs: b64_enc_opus, or the
//               b64_enc_pcm_s16le fallback. The fallback is ~340 kbit/s on the wire
//               against Opus's ~43, which on this link is the difference between
//               keeping up and never catching up.
//
// Then the second-order problem, which only shows up in a long session:
//
//   longtasks=  main-thread time in tasks over 50ms
//   addMessage= per-call cost of the site's transcript renderer, and dom= the transcript
//               size it is proportional to. Rendering cost grows linearly with transcript
//               length, so total work is quadratic and a session degrades as it runs.
//
// Metadata only -- counts, durations, sizes, states. Never headers, never bodies, and
// paths are taken without query strings, so no session token can reach a log you send on.
private val PERF_TRACE_JS = """
    (function () {
        if (window.__appPerfTrace) return;
        window.__appPerfTrace = true;
        var TAG = '[perf]';
        var SUMMARY_MS = 5000;

        // See NET_TRACE_JS's redactIds -- same reasoning: the session id is a long
        // digit/hex path segment, not a query param, so it needs its own placeholder.
        function redactIds(p) {
            return p.replace(/\/[0-9a-f]{20,}/gi, '/<id>');
        }
        function pathOf(url) {
            try { return redactIds(new URL(url, location.href).pathname); }
            catch (e) { return redactIds(String(url).split('?')[0]); }
        }
        function stat(a) {
            if (!a.length) return 'n=0';
            var s = a.slice().sort(function (x, y) { return x - y; });
            return 'p50=' + Math.round(s[Math.floor(s.length * 0.5)]) + 'ms'
                 + ' max=' + Math.round(s[s.length - 1]) + 'ms';
        }
        function once(key, msg) {
            if (window['__perfSeen_' + key]) return;
            window['__perfSeen_' + key] = true;
            console.log(TAG + ' ' + msg);
        }

        // ---- stage 1: getUserMedia --------------------------------------------------
        // Reports what the browser actually applied, which is not what the site asked for:
        // the site puts echoCancellation at the top level of the constraints object where
        // the spec ignores it (see CLAUDE.md, "Raw capture vs. USB routing").
        try {
            var gum = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
            navigator.mediaDevices.getUserMedia = function (constraints) {
                var t0 = performance.now();
                return gum(constraints).then(function (stream) {
                    var track = stream.getAudioTracks()[0];
                    var s = {};
                    try { s = track.getSettings(); } catch (e) {}
                    console.log(TAG + ' getUserMedia ok after ' + Math.round(performance.now() - t0)
                        + 'ms: ' + (s.sampleRate || '?') + 'Hz ch=' + (s.channelCount || '?')
                        + ' ec=' + s.echoCancellation + ' ns=' + s.noiseSuppression
                        + ' agc=' + s.autoGainControl);
                    return stream;
                }, function (err) {
                    console.log(TAG + ' getUserMedia FAILED after '
                        + Math.round(performance.now() - t0) + 'ms: ' + err);
                    throw err;
                });
            };
        } catch (e) {
            console.log(TAG + ' cannot wrap getUserMedia: ' + e);
        }

        // ---- stage 2: AudioContext --------------------------------------------------
        // The single most likely reason for a completely empty transcript. The site
        // constructs its AudioContext from a load-time recording() call with no user
        // gesture, and never calls resume(), so a browser that enforces a gesture
        // requirement leaves it suspended forever and no callback ever fires.
        var ctxRef = null;
        try {
            var NativeCtx = window.AudioContext || window.webkitAudioContext;
            if (NativeCtx) {
                var Wrapped = function () {
                    var c = new NativeCtx(arguments[0]);
                    ctxRef = c;
                    console.log(TAG + ' AudioContext created: state=' + c.state
                        + ' sampleRate=' + c.sampleRate);
                    if (c.state !== 'running') {
                        console.log(TAG + ' AudioContext is NOT running -- capture is dead'
                            + ' until something resumes it. The page never calls resume().');
                    }
                    c.addEventListener('statechange', function () {
                        console.log(TAG + ' AudioContext state -> ' + c.state);
                    });
                    return c;
                };
                Wrapped.prototype = NativeCtx.prototype;
                window.AudioContext = Wrapped;
                if (window.webkitAudioContext) window.webkitAudioContext = Wrapped;
            }
        } catch (e) {
            console.log(TAG + ' cannot wrap AudioContext: ' + e);
        }

        // ---- stage 3: the resampler, and the callback that uses it -------------------
        // Wrapped through the prototype's own accessor so the native dispatch path is
        // unchanged; we only time and guard the site's handler. Injected from
        // onPageStarted so this lands before the page builds its graph.
        var cbDur = [], cbGap = [], cbErrs = 0, firstCbErr = null;
        var cbExpectMs = 0;
        try {
            var csp = (window.AudioContext && AudioContext.prototype.createScriptProcessor)
                ? AudioContext.prototype.createScriptProcessor : null;
            if (csp) {
                AudioContext.prototype.createScriptProcessor = function (bufferSize) {
                    var node = csp.apply(this, arguments);
                    cbExpectMs = bufferSize / this.sampleRate * 1000;
                    console.log(TAG + ' ScriptProcessor ' + bufferSize + ' frames @'
                        + this.sampleRate + 'Hz -> a main-thread callback every '
                        + Math.round(cbExpectMs) + 'ms. resampler='
                        + (typeof window.waveResampler));
                    try {
                        var proto = Object.getPrototypeOf(node);
                        var desc = null;
                        while (proto && !desc) {
                            desc = Object.getOwnPropertyDescriptor(proto, 'onaudioprocess');
                            if (!desc) proto = Object.getPrototypeOf(proto);
                        }
                        if (desc && desc.set) {
                            var last = 0;
                            Object.defineProperty(node, 'onaudioprocess', {
                                configurable: true,
                                get: function () { return desc.get.call(this); },
                                set: function (fn) {
                                    desc.set.call(this, function (ev) {
                                        var t0 = performance.now();
                                        if (last) cbGap.push(t0 - last);
                                        last = t0;
                                        try {
                                            return fn.call(this, ev);
                                        } catch (err) {
                                            // The site does not catch this. One throw per
                                            // callback = permanent silence, no UI change.
                                            cbErrs++;
                                            if (!firstCbErr) {
                                                firstCbErr = String(err);
                                                console.log(TAG + ' onaudioprocess THREW: ' + err
                                                    + ' | waveResampler=' + (typeof window.waveResampler));
                                            }
                                        } finally {
                                            cbDur.push(performance.now() - t0);
                                        }
                                    });
                                }
                            });
                        }
                    } catch (e) {
                        console.log(TAG + ' cannot instrument onaudioprocess: ' + e);
                    }
                    return node;
                };
            }
        } catch (e) {
            console.log(TAG + ' cannot wrap createScriptProcessor: ' + e);
        }

        // ---- stage 4: the WebCodecs Opus encoder ------------------------------------
        // encode() calls and the packets the output callback actually produced. These
        // two diverging is the signature of an encoder that accepts audio and emits
        // nothing, which the page has no way to notice.
        var encCalls = 0, encPackets = 0, encBytes = 0;
        try {
            if (typeof AudioEncoder !== 'undefined') {
                var NativeEnc = AudioEncoder;
                var WrappedEnc = function (init) {
                    var wrappedInit = {
                        output: function (chunk, meta) {
                            encPackets++;
                            encBytes += (chunk && chunk.byteLength) || 0;
                            return init.output.apply(this, arguments);
                        },
                        error: function (e) {
                            console.log(TAG + ' AudioEncoder ERROR: ' + e
                                + ' -- page falls back to base64 PCM from here on');
                            return init.error.apply(this, arguments);
                        }
                    };
                    var enc = new NativeEnc(wrappedInit);
                    var origConfigure = enc.configure.bind(enc);
                    enc.configure = function (cfg) {
                        console.log(TAG + ' AudioEncoder.configure ' + JSON.stringify(cfg));
                        return origConfigure(cfg);
                    };
                    var origEncode = enc.encode.bind(enc);
                    enc.encode = function () {
                        encCalls++;
                        try {
                            return origEncode.apply(null, arguments);
                        } catch (e) {
                            once('encThrow', 'AudioEncoder.encode THREW: ' + e);
                            throw e;
                        }
                    };
                    return enc;
                };
                WrappedEnc.isConfigSupported = function (cfg) {
                    return NativeEnc.isConfigSupported(cfg).then(function (r) {
                        console.log(TAG + ' AudioEncoder.isConfigSupported('
                            + JSON.stringify(cfg) + ') -> ' + r.supported);
                        return r;
                    });
                };
                WrappedEnc.prototype = NativeEnc.prototype;
                window.AudioEncoder = WrappedEnc;
            } else {
                console.log(TAG + ' no AudioEncoder in this browser'
                    + ' -- page will use the base64 PCM fallback (~340 kbit/s uplink)');
            }
        } catch (e) {
            console.log(TAG + ' cannot wrap AudioEncoder: ' + e);
        }

        // ---- stage 5: which payload actually goes up --------------------------------
        var uploads = { opus: 0, pcm: 0, pause: 0, bytes: 0 };
        try {
            var origFetch = window.fetch;
            window.fetch = function (input, init) {
                try {
                    var url = (typeof input === 'string') ? input : (input && input.url) || '';
                    if (pathOf(url).indexOf('/append') >= 0 && init && typeof init.body === 'string') {
                        uploads.bytes += init.body.length;
                        if (init.body.indexOf('b64_enc_opus') >= 0) uploads.opus++;
                        else if (init.body.indexOf('PAUSE') >= 0) uploads.pause++;
                        else if (init.body.indexOf('b64_enc_pcm_s16le') >= 0) {
                            uploads.pcm++;
                            once('pcmPath', 'upload is using the base64 PCM FALLBACK,'
                                + ' not Opus -- roughly 8x the uplink bitrate');
                        }
                    }
                } catch (e) {}
                return origFetch.apply(this, arguments);
            };
        } catch (e) {
            console.log(TAG + ' cannot wrap fetch for payload classification: ' + e);
        }

        // ---- the site's own upload lag banner ---------------------------------------
        // #upload-warning carries the one number nothing else exposes: how many seconds of
        // audio a single request actually contained. Steady state is ~1s; larger means a
        // backlog draining.
        var lastWarning = '';
        setInterval(function () {
            var el = document.getElementById('upload-warning');
            var txt = el ? (el.textContent || '').trim() : '';
            if (txt && txt !== lastWarning) {
                lastWarning = txt;
                console.log(TAG + ' upload-warning: ' + txt);
            }
        }, 1000);

        // ---- second-order: main-thread cost of transcript rendering -----------------
        var longTasks = { n: 0, ms: 0, max: 0 };
        try {
            new PerformanceObserver(function (list) {
                list.getEntries().forEach(function (e) {
                    longTasks.n++;
                    longTasks.ms += e.duration;
                    if (e.duration > longTasks.max) longTasks.max = e.duration;
                });
            }).observe({ entryTypes: ['longtask'] });
        } catch (e) {
            console.log(TAG + ' longtask observer unavailable: ' + e);
        }

        // The site issues SYNCHRONOUS XMLHttpRequests from its SSE handler
        // (get_output_language_component, get_previous_messages). Each freezes the page
        // for a full round trip, which on this link is 150-250ms before the server starts.
        var syncXhr = { n: 0, ms: 0 };
        try {
            var xhrOpen = XMLHttpRequest.prototype.open;
            var xhrSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function (method, url, async) {
                this.__perfSync = (async === false);
                this.__perfPath = pathOf(url);
                return xhrOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function () {
                if (!this.__perfSync) return xhrSend.apply(this, arguments);
                var t0 = performance.now();
                try {
                    return xhrSend.apply(this, arguments);
                } finally {
                    var ms = performance.now() - t0;
                    syncXhr.n++;
                    syncXhr.ms += ms;
                    console.log(TAG + ' SYNC-XHR ' + this.__perfPath
                        + ' froze main thread ' + Math.round(ms) + 'ms');
                }
            };
        } catch (e) {
            console.log(TAG + ' cannot wrap XMLHttpRequest: ' + e);
        }

        // EventSource is the transcript return channel. NET_TRACE_JS wraps only fetch and
        // WebSocket, so without this the whole downstream is invisible. Claiming the name
        // here also keeps the page on the native implementation: the site's 279 KB
        // eventsource-polyfill installs itself only when window.EventSource is falsy.
        var sse = { msgs: 0, bytes: 0 };
        try {
            var NativeES = window.EventSource;
            if (NativeES) {
                var TracedES = function (url, cfg) {
                    var es = new NativeES(url, cfg);
                    var t0 = performance.now();
                    es.addEventListener('open', function () {
                        console.log(TAG + ' SSE open ' + pathOf(url)
                            + ' after ' + Math.round(performance.now() - t0) + 'ms');
                    });
                    es.addEventListener('error', function () {
                        console.log(TAG + ' SSE error on ' + pathOf(url)
                            + ' readyState=' + es.readyState
                            + (es.readyState === 0 ? ' (reconnecting)' : ''));
                    });
                    es.addEventListener('message', function (ev) {
                        sse.msgs++;
                        sse.bytes += (ev.data ? ev.data.length : 0);
                    });
                    return es;
                };
                TracedES.prototype = NativeES.prototype;
                TracedES.CONNECTING = 0;
                TracedES.OPEN = 1;
                TracedES.CLOSED = 2;
                window.EventSource = TracedES;
            }
        } catch (e) {
            console.log(TAG + ' cannot wrap EventSource: ' + e);
        }

        // addMessage is a top-level function declaration in one of the page's inline
        // scripts, so it lands on window -- but only once that script has run.
        var render = [];
        var wrapped = false, tries = 0;
        var wrapTimer = setInterval(function () {
            if (!wrapped && typeof window.addMessage === 'function') {
                var orig = window.addMessage;
                window.addMessage = function () {
                    var t0 = performance.now();
                    try { return orig.apply(this, arguments); }
                    finally { render.push(performance.now() - t0); }
                };
                wrapped = true;
                console.log(TAG + ' addMessage instrumented');
            }
            if (wrapped || ++tries > 60) clearInterval(wrapTimer);
        }, 500);

        // ---- rollup -----------------------------------------------------------------
        setInterval(function () {
            var parts = [];
            parts.push('ctx=' + (ctxRef ? ctxRef.state : 'none'));
            parts.push('resampler=' + (typeof window.waveResampler));
            parts.push('cb=' + cbDur.length
                + (cbDur.length ? ' ' + stat(cbDur) + ' gap ' + stat(cbGap)
                    + ' expect=' + Math.round(cbExpectMs) + 'ms' : '')
                + (cbErrs ? ' cbErr=' + cbErrs : ''));
            parts.push('enc=' + encCalls + ' pkt=' + encPackets
                + ' ' + Math.round(encBytes / 1024) + 'KB');
            parts.push('up opus=' + uploads.opus + ' pcm=' + uploads.pcm
                + ' pause=' + uploads.pause + ' ' + Math.round(uploads.bytes / 1024) + 'KB');
            if (sse.msgs) parts.push('sse n=' + sse.msgs + ' ' + Math.round(sse.bytes / 1024) + 'KB');
            if (syncXhr.n) parts.push('syncXHR n=' + syncXhr.n + ' froze=' + Math.round(syncXhr.ms) + 'ms');
            if (longTasks.n) {
                parts.push('longtasks=' + longTasks.n + ' blocked=' + Math.round(longTasks.ms)
                    + 'ms max=' + Math.round(longTasks.max) + 'ms');
            }
            if (render.length) parts.push('addMessage n=' + render.length + ' ' + stat(render));
            try {
                parts.push('dom words=' + document.querySelectorAll('.word').length);
            } catch (e) {}

            // The page has an iframe (runtime-correction-setting) that will never have an
            // AudioContext or a transcript. Staying quiet there keeps the log readable.
            var worthPrinting = ctxRef || sse.msgs || syncXhr.n || render.length || cbDur.length;
            if (worthPrinting) console.log(TAG + ' ' + parts.join(' | '));

            cbDur = []; cbGap = [];
            encCalls = 0; encPackets = 0; encBytes = 0;
            uploads = { opus: 0, pcm: 0, pause: 0, bytes: 0 };
            sse = { msgs: 0, bytes: 0 };
            syncXhr = { n: 0, ms: 0 };
            longTasks = { n: 0, ms: 0, max: 0 };
            render = [];
        }, SUMMARY_MS);

        console.log(TAG + ' tracing armed');
    })();
""".trimIndent()

// The transcript renderer the wrapped site ships is quadratic in session length: it
// redoes full passes over the accumulated transcript on every incoming message, and
// auto-scrolls by writing scrollTop inline, which forces a synchronous layout ~4x per
// ASR cycle. That work lands on the same main thread as the ScriptProcessorNode audio
// callback, which DROPS input it cannot collect in time -- so a long lecture starts
// eating its own audio. Measured, fixed and regression-tested in tools/; see
// CLAUDE.md "the transcript renderer is quadratic" for the numbers.
//
// Kept as its own constant rather than folded into SITE_TWEAKS_JS so the harnesses in
// tools/ can extract and drive exactly the JS the app ships (tools/kotlin-js.mjs).
private val RENDERER_FIX_JS = """
    // renderer-fix.js -- removes the per-message O(n)/O(chapters) work from the wrapped
    // site's transcript renderer so a 90-minute lecture does not degrade.
    //
    // Context: see ../CLAUDE.md ("Known second-order problem: the transcript renderer is
    // quadratic") and tools/README.md. The renderer's own functions
    // (show_text, add_selecting_word_action, addMessage, createChapterElement,
    // clearMarkupWindow) are plain top-level `function` declarations in the wrapped page --
    // not inside an IIFE -- so they are real `window` properties, and reassigning
    // `window.<name> = ...` takes effect for calls made from *inside* the site's own code,
    // because those internal calls resolve the identifier through the shared global
    // environment. Verified empirically (see tools/renderer-equivalence-check.mjs and the
    // probes run while developing this file) before relying on it here.
    //
    // HARD REQUIREMENTS this file honours:
    //   - ES5-only for OUR OWN code (no arrow functions, no template literals, no optional
    //     chaining, no let/const at file scope where it would matter) -- injected into an
    //     Android WebView via evaluateJavascript. The one exception is the *rebuilt*
    //     addMessage body below, which is the SITE's own ES6 source text re-evaluated
    //     verbatim (minus a few substituted lines) -- that's not new code we're authoring,
    //     and the WebView already runs the site's ES6 elsewhere.
    //   - Fully idempotent: this whole file is injected multiple times per page load
    //     (onPageStarted, +2s, +10s, onPageFinished). Every install step below guards itself
    //     so re-running is always a safe no-op.
    //   - Must never throw: shares an injection with the app's capture fixes
    //     (patchAudioContext / applyCaptureMode in SITE_TWEAKS_JS); an uncaught exception
    //     here must not be able to take those out. Everything is wrapped in try/catch and
    //     degrades to leaving the site's original functions in place.
    //   - The site's functions may not exist yet when this first runs -- each install step
    //     reports whether it actually ran, and the bottom of the file retries on a schedule
    //     until everything is installed.
    //
    // What is intentionally NOT touched: update_speaker_tag / updateSpeakerTagsForSection.
    // It rebuilds speaker tags across every chapter and is potentially the heaviest
    // per-call cost, but it only fires when messages carry `speakerName`, retroactive
    // `refined_sentence_cluster` corrections can invalidate chapters other than the
    // current one, and there is no bench coverage for it here -- an incremental version
    // would not be safely verifiable with what this repo has. See CLAUDE.md's "explicitly
    // out of scope" note.

    (function () {
      // Deliberately NOT 'use strict': installAddMessageRewrite() below rebuilds addMessage
      // via a direct `eval`, which inherits strict-mode-ness from its call site. The site's
      // own addMessage relies on at least one undeclared-global assignment
      // (`unstable = document.getElementById(...)`, no var/let/const) that is a hard error
      // in strict mode. Running non-strict here matches the sloppy mode the site's own
      // scripts run in, so the rebuilt function behaves exactly like the original.

      function log(msg) {
        try { console.log('[renderer-fix] ' + msg); } catch (e) { /* console unavailable */ }
      }

      if (!window.__rendererFix) {
        window.__rendererFix = { installed: false };
      }
      if (window.__rendererFix.installed) {
        return; // already fully installed by an earlier injection -- nothing to do
      }

      // ---------------------------------------------------------------------------------
      // Hotspot #1: show_text() -- O(n) insertion-point scan on every stable message.
      //
      // The original does:
      //   const spans = w.getElementsByTagName('span');
      //   ... var l = spans.length;
      //   for (var i = 0; i < l; i++) if (parseFloat(spans[i].getAttribute("start")) < start) last = i;
      //   spans[last].after(newSpan)
      // ASR timestamps increase monotonically, so in the common case the right insertion
      // point is simply "right after whatever we inserted last time". We cache that per
      // window element and only fall back to the full scan when the cache is missing,
      // stale (its node got detached -- covers action==="remove"/"replace", the
      // `w.innerHTML = ""` CLEAR path, and clearMarkupWindow, all of which restructure the
      // window and are caught for free by "is the cached node still w's child"), or the
      // incoming start is behind the cached start (an out-of-order backfill from
      // get_previous_messages).
      // ---------------------------------------------------------------------------------
      function installShowTextCache() {
        if (typeof window.show_text !== 'function') return false; // site not loaded yet
        if (window.show_text.__rendererFixWrapped) return true; // already patched

        var original = window.show_text;
        var cache = typeof WeakMap === 'function' ? new WeakMap() : null;
        if (!cache) return true; // no WeakMap available -- leave original in place, permanently

        function fastAdd(w, seq, start, end, type, spk, additionalClasses) {
          var newSpan = document.createElement(type);
          newSpan.innerHTML = seq;
          newSpan.setAttribute('start', start);
          newSpan.setAttribute('end', end);
          newSpan.setAttribute('speaker-title', spk);
          for (var c = 0; c < additionalClasses.length; c++) newSpan.classList.add(additionalClasses[c]);

          // Same blank-content early return the original has, before any insertion.
          if (newSpan.innerHTML.trim() === '') return undefined;

          var entry = cache.get(w);
          var cacheValid = !!(entry && entry.node && entry.node.parentNode === w);

          if (cacheValid && start >= entry.start) {
            entry.node.after(newSpan);
            cache.set(w, { node: newSpan, start: start });
            return newSpan;
          }

          // Fallback: identical full scan to the original. Note this scan is over ALL
          // descendant <span> elements, not just w's direct children: each inserted
          // top-level span (class plain_transcript, carrying the start/end attributes) has
          // its own per-word <span class="word"> children with NO start attribute, and
          // getElementsByTagName is recursive, so the flat list interleaves top-level
          // wrapper spans with their nested word spans. parseFloat(null) is NaN and
          // `NaN < start` is always false, so nested word spans never win the `last` index
          // below -- exactly like the original, which has the same property.
          var spans = w.getElementsByTagName('span');
          var l = spans.length;
          if (l === 0) {
            w.appendChild(newSpan);
            cache.set(w, { node: newSpan, start: start }); // trivially the new rightmost span
            return newSpan;
          }
          var last = 0;
          // lastWrapperIdx tracks the highest index of ANY element that has a real (non-NaN)
          // start attribute -- i.e. any top-level wrapper span, regardless of whether it lost
          // the `last` comparison. This is what "was the insertion at the true end" needs to
          // check against -- NOT `l - 1`, which is the index of the last *nested word* span
          // when the wrapper we just inserted after already has word children (the common
          // case): those nested children sort after their own wrapper in document order, so
          // `l - 1` almost never equals the wrapper's own index even when it genuinely is the
          // last wrapper. Comparing against the last WRAPPER index instead of the last NODE
          // index is what makes this correct.
          var lastWrapperIdx = -1;
          for (var i = 0; i < l; i++) {
            var sv = parseFloat(spans[i].getAttribute('start'));
            if (!isNaN(sv)) {
              lastWrapperIdx = i;
              if (sv < start) last = i;
            }
          }
          spans[last].after(newSpan);
          // Only trust this as the new "rightmost" cache entry if the wrapper we just
          // inserted after was itself the last wrapper span overall -- i.e. this was NOT a
          // backfill into the middle of the window. If it was, the true rightmost span is
          // unchanged, so leave any existing cache entry alone rather than pointing it at
          // the wrong (earlier) node.
          if (lastWrapperIdx === -1 || last === lastWrapperIdx) {
            cache.set(w, { node: newSpan, start: start });
          }
          return newSpan;
        }

        function fastShowText(w, seq, start, end, type, action, spk, additionalClasses) {
          if (type === undefined) type = 'span';
          if (action === undefined) action = 'add';
          if (!additionalClasses) additionalClasses = [];

          if (action !== 'add') {
            // remove/replace are rare (site-internal corrections), can reorder or delete
            // spans, and are not part of the hot path this fix targets. Delegate to the
            // site's own implementation unchanged -- including whatever the site's own
            // pre-existing behaviour is (e.g. the `replace` branch reads a variable,
            // isMouseDown, that is never declared anywhere in either vendored capture --
            // a latent bug in the site itself, not something to paper over here).
            if (cache.has(w)) cache.delete(w); // conservative: might reorder/delete spans
            return original.call(this, w, seq, start, end, type, action, spk, additionalClasses);
          }

          try {
            return fastAdd(w, seq, start, end, type, spk, additionalClasses);
          } catch (e) {
            log('show_text fast path threw, falling back to original: ' + (e && e.message));
            if (cache.has(w)) cache.delete(w);
            return original.call(this, w, seq, start, end, type, action, spk, additionalClasses);
          }
        }

        fastShowText.__rendererFixWrapped = true;
        window.show_text = fastShowText;
        return true;
      }

      // ---------------------------------------------------------------------------------
      // Hotspot #2: add_selecting_word_action() -- re-queries every `.word` in the window
      // on every stable message, then guards per-node with `word.eventListenersAdded`. The
      // guard does not save the query itself, which is the actual O(n) cost. Fixed with
      // event delegation: bind mouseover/mouseout/contextmenu ONCE per window container and
      // resolve the word via event.target.closest('.word'); the original function becomes
      // a one-time delegator.
      //
      // The handler bodies reference module-private state from the site's own script
      // (customMenu, highlightedWord, post_message, word_) as bare identifiers -- these are
      // top-level const/let/var in the page's *other* inline <script> blocks, not `window`
      // properties, but classic (non-module) <script> tags in one document share a single
      // Global Environment, so they resolve correctly from here too (verified empirically:
      // typeof customMenu / post_message / word_ / highlightedWord all resolve to their
      // real values from an independently-evaluated script in the same page). The
      // contextmenu handler still does its own document.querySelectorAll('.word') scan --
      // left alone deliberately, since it only runs on an actual right-click, not per
      // message.
      // ---------------------------------------------------------------------------------
      function installWordDelegation() {
        if (typeof window.add_selecting_word_action !== 'function') return false;
        if (window.add_selecting_word_action.__rendererFixWrapped) return true;

        function closestWord(node) {
          while (node && node.nodeType === 1) {
            if (node.classList && node.classList.contains('word')) return node;
            node = node.parentNode;
          }
          return null;
        }

        function delegate(w) {
          if (w.__rendererFixWordDelegated) return;
          w.__rendererFixWordDelegated = true;

          w.addEventListener('mouseover', function (e) {
            var word = closestWord(e.target);
            if (!word) return;
            if (!highlightedWord) {
              word.classList.add('highlight');
            }
          });

          w.addEventListener('mouseout', function (e) {
            var word = closestWord(e.target);
            if (!word) return;
            if (!highlightedWord || highlightedWord !== word) {
              word.classList.remove('highlight');
            }
          });

          w.addEventListener('contextmenu', function (e) {
            var word = closestWord(e.target);
            if (!word) return;
            e.preventDefault();
            customMenu.style.display = 'block';
            customMenu.style.left = e.pageX + 'px';
            customMenu.style.top = e.pageY + 'px';

            var wordOrder = word.getAttribute('word-order');
            var text = word.innerText;
            var session_id = word.getAttribute('word-session-id');
            var stream_id = word.getAttribute('word-stream-id');

            var allWordsRaw = document.querySelectorAll('.word');
            var all_words = [];
            for (var i = 0; i < allWordsRaw.length; i++) {
              var ww = allWordsRaw[i];
              if (ww.getAttribute('word-session-id') === session_id && ww.getAttribute('word-stream-id') === stream_id) {
                all_words.push(ww);
              }
            }
            var word_order = [];
            for (var j = 0; j < all_words.length; j++) word_order.push(all_words[j].getAttribute('word-order'));
            var minOrder = Math.min.apply(Math, word_order);
            var maxOrder = Math.max.apply(Math, word_order);
            var all_word_order = [minOrder, maxOrder];
            var profileSelect = document.getElementById('profile');
            var profile_id = '' || (profileSelect ? profileSelect.value : '');

            if (highlightedWord) {
              highlightedWord.classList.remove('highlight');
            }
            highlightedWord = word;
            word.classList.add('highlight');

            post_message = {
              text: text, wordOrder: wordOrder, session_id: session_id, stream_id: stream_id,
              all_word_order: all_word_order, profile_id: profile_id
            };
            word_ = word;
          });
        }

        function wrapped(w) {
          try {
            if (w === null || w === undefined) return;
            // Same "is this UI even present" guard the original used -- if none of these
            // exist, the original never wired up any listeners either.
            if (document.getElementById('turn-setting') === null &&
                document.getElementById('context-setting') === null &&
                document.getElementById('correction-setting') === null) {
              return;
            }
            delegate(w);
          } catch (e) {
            log('add_selecting_word_action delegation threw: ' + (e && e.message));
          }
        }

        wrapped.__rendererFixWrapped = true;
        window.add_selecting_word_action = wrapped;
        return true;
      }

      // ---------------------------------------------------------------------------------
      // Hotspot #3 (scroll-to-bottom) + #4 (.text-original re-query), folded into one
      // source-level rewrite of addMessage() because both live inside that one large
      // function body:
      //
      //  #3: `wAtBottom`/`wMarkupAtBottom` are computed from scrollHeight/clientHeight/
      //      scrollTop on EVERY call (both stable and partial branches), and the
      //      `el.scrollTop = el.scrollHeight` writes run on both branches too -- BOTH force
      //      synchronous layout. The read forces it obviously; the write is less obvious but
      //      just as real in Blink: writing scrollTop must clamp to the current scroll
      //      range, which requires up-to-date layout, exactly like reading scrollHeight
      //      does. (An earlier version of this file assumed a sentinel write -- `el.scrollTop
      //      = 1e9` -- would dodge that cost. Measured on-device, it did not: ~18ms of a
      //      ~2700ms final bucket was in any named function, the rest was this write,
      //      forced on both branches, ~4x per ASR cycle.)
      //
      //      Fix, both sides: the READ is backed by a value tracked from a 'scroll'
      //      listener (updated only when a real scroll event fires) instead of a fresh
      //      layout read every message -- this is the at-bottom DECISION, made once per
      //      addMessage call, exactly where the original decided it. The WRITE is coalesced
      //      into a requestAnimationFrame: at most one pending write per container, so N
      //      messages in one frame collapse into a single scrollTop write, which lands at a
      //      point where the browser was already about to do layout to paint -- it stops
      //      being a *forced synchronous* layout in the middle of addMessage. See
      //      scheduleScrollToBottom() below.
      //
      //  #4: the unstable/partial path re-queries `wMarkup.querySelectorAll('.text-original')`
      //      then takes `field[field.length - 1]` -- O(chapters), a smaller win than #1/#2,
      //      done here because the rewrite already has to touch this function. Cached per
      //      wMarkup, invalidated when a chapter is added (createChapterElement) or the
      //      window is wiped (clearMarkupWindow).
      //
      // Mechanism: addMessage is a plain top-level function (not inside an IIFE), so
      // Function.prototype.toString() returns its exact original source text. We take that
      // text, substitute a small number of exact-match lines, and re-create the function
      // with `eval`. A function created this way still resolves free identifiers (show_text,
      // manager, sender, createChapterElement, ...) through the page's single shared global
      // environment -- confirmed empirically before relying on this (see
      // tools/renderer-equivalence-check.mjs) -- so it behaves exactly like the original
      // function would, except for the substituted lines.
      //
      // If the site's addMessage doesn't contain every expected pattern (the live site has
      // drifted from what this was written against), we do NOT apply a partial rewrite --
      // we leave the original addMessage untouched and log loudly, rather than risk
      // silently breaking scrolling or rendering.
      // ---------------------------------------------------------------------------------
      function installAddMessageRewrite() {
        if (typeof window.addMessage !== 'function') return false;
        if (window.addMessage.__rendererFixWrapped) return true;

        // --- at-bottom tracking, backing hotspot #3's read side ---
        var atBottom = typeof WeakMap === 'function' ? new WeakMap() : null;
        if (!atBottom) return true; // no WeakMap -- leave addMessage untouched, permanently

        function ensureTracked(el) {
          if (!el || el.__rendererFixBottomTracked) return;
          el.__rendererFixBottomTracked = true;
          function recompute() {
            try {
              atBottom.set(el, (el.scrollHeight - el.clientHeight) <= (el.scrollTop + 1));
            } catch (e) { /* ignore */ }
          }
          recompute(); // one-time seed read, amortized over the whole session
          el.addEventListener('scroll', recompute, { passive: true });
        }

        window.__rendererFixAtBottom = function (el) {
          if (!el) return true;
          ensureTracked(el);
          return atBottom.has(el) ? atBottom.get(el) : true;
        };

        // --- coalesced scroll-to-bottom writes, replacing the sentinel-write attempt ---
        // A sentinel write (`el.scrollTop = 1e9`) does NOT avoid forcing layout in Blink:
        // writing scrollTop must clamp to the current scroll range, which requires
        // up-to-date layout just like reading scrollHeight does. Measured on-device (see the
        // coordinator's profile): with the sentinel write in place, ~18ms of a ~2700ms final
        // bucket was in any named function -- the rest was this write, forced on both the
        // partial and stable branches (~4x per ASR cycle). Fix: coalesce the write into a
        // requestAnimationFrame, at most one pending write per container. The at-bottom
        // DECISION still happens at schedule time (addMessage's own wAtBottom/
        // wMarkupAtBottom check, backed by window.__rendererFixAtBottom above, itself backed
        // by a scroll listener, not a fresh layout read) -- only the WRITE is deferred to the
        // next frame, where the browser was going to do layout anyway to paint. This means a
        // message arriving while the user is scrolled up never schedules a write at all, and
        // multiple messages within one frame collapse into a single write.
        var scrollScheduled = typeof WeakMap === 'function' ? new WeakMap() : null;

        function scheduleScrollToBottom(el) {
          if (!el) return;
          if (typeof requestAnimationFrame !== 'function' || !scrollScheduled) {
            // No rAF (or no WeakMap) available -- fall back to the immediate write this
            // replaces. Still correct, just not coalesced.
            try { el.scrollTop = el.scrollHeight; } catch (e) { /* ignore */ }
            return;
          }
          if (scrollScheduled.get(el)) return; // a write for this container is already pending
          scrollScheduled.set(el, true);
          requestAnimationFrame(function () {
            scrollScheduled.set(el, false);
            try { el.scrollTop = el.scrollHeight; } catch (e) { /* ignore */ }
          });
        }

        window.__rendererFixScheduleScroll = scheduleScrollToBottom;

        // --- last .text-original cache, backing hotspot #4 ---
        var textOriginalCache = typeof WeakMap === 'function' ? new WeakMap() : null;
        window.__rendererFixLastTextOriginal = function (wMarkup) {
          if (!textOriginalCache) {
            return wMarkup.querySelectorAll('.text-original'); // no WeakMap -- behave as original
          }
          var cached = textOriginalCache.get(wMarkup);
          if (cached && wMarkup.contains(cached)) {
            return [cached];
          }
          var fields = wMarkup.querySelectorAll('.text-original');
          var last = fields.length ? fields[fields.length - 1] : undefined;
          textOriginalCache.set(wMarkup, last);
          return [last];
        };

        function invalidateTextOriginal(wMarkup) {
          if (textOriginalCache && wMarkup) textOriginalCache.delete(wMarkup);
        }

        if (typeof window.createChapterElement === 'function' && !window.createChapterElement.__rendererFixWrapped) {
          var origCreateChapterElement = window.createChapterElement;
          var wrappedCCE = function (language) {
            var result = origCreateChapterElement.apply(this, arguments);
            try {
              invalidateTextOriginal(document.getElementById('window-content-structured-' + language));
            } catch (e) { /* ignore */ }
            return result;
          };
          wrappedCCE.__rendererFixWrapped = true;
          window.createChapterElement = wrappedCCE;
        }

        if (typeof window.clearMarkupWindow === 'function' && !window.clearMarkupWindow.__rendererFixWrapped) {
          var origClearMarkupWindow = window.clearMarkupWindow;
          var wrappedCMW = function (index) {
            try {
              invalidateTextOriginal(document.getElementById('window-content-structured-' + index));
            } catch (e) { /* ignore */ }
            return origClearMarkupWindow.apply(this, arguments);
          };
          wrappedCMW.__rendererFixWrapped = true;
          window.clearMarkupWindow = wrappedCMW;
        }

        // --- the source rewrite itself ---
        var src;
        try {
          src = window.addMessage.toString();
        } catch (e) {
          return true; // can't introspect the function -- give up permanently, safely
        }

        var linePatches = [
          ['const wAtBottom = w.scrollHeight - w.clientHeight <= w.scrollTop + 1;',
            'const wAtBottom = window.__rendererFixAtBottom(w);'],
          ['const wMarkupAtBottom = wMarkup.scrollHeight - wMarkup.clientHeight <= wMarkup.scrollTop + 1;',
            'const wMarkupAtBottom = window.__rendererFixAtBottom(wMarkup);'],
          ['w.scrollTop = w.scrollHeight;', 'window.__rendererFixScheduleScroll(w);'],
          ['wMarkup.scrollTop = wMarkup.scrollHeight;', 'window.__rendererFixScheduleScroll(wMarkup);']
        ];

        var newSrc = src;
        var appliedCount = 0;
        for (var i = 0; i < linePatches.length; i++) {
          if (newSrc.indexOf(linePatches[i][0]) !== -1) {
            newSrc = newSrc.split(linePatches[i][0]).join(linePatches[i][1]);
            appliedCount++;
          }
        }

        var textOriginalNeedle = 'const field = wMarkup.querySelectorAll(`.text-original`)';
        var textOriginalCount = newSrc.split(textOriginalNeedle).length - 1;
        if (textOriginalCount > 0) {
          newSrc = newSrc.split(textOriginalNeedle).join('const field = window.__rendererFixLastTextOriginal(wMarkup)');
        }

        if (appliedCount < linePatches.length || textOriginalCount === 0) {
          log('addMessage source did not match all expected patterns (scroll patches ' +
            appliedCount + '/' + linePatches.length + ', text-original matches ' + textOriginalCount +
            '); leaving addMessage unpatched (hotspot #1/#2 fixes still apply)');
          return true; // permanent, informed skip -- not something retrying will fix
        }

        var rebuilt;
        try {
          rebuilt = eval('(' + newSrc + ')'); // eslint-disable-line no-eval
        } catch (e) {
          log('failed to rebuild addMessage, leaving original in place: ' + (e && e.message));
          return true;
        }
        if (typeof rebuilt !== 'function') return true;

        rebuilt.__rendererFixWrapped = true;
        window.addMessage = rebuilt;
        return true;
      }

      function tryInstall() {
        var okShowText = false, okDelegation = false, okAddMessage = false;
        try { okShowText = installShowTextCache(); } catch (e) { log('installShowTextCache threw: ' + (e && e.message)); }
        try { okDelegation = installWordDelegation(); } catch (e) { log('installWordDelegation threw: ' + (e && e.message)); }
        try { okAddMessage = installAddMessageRewrite(); } catch (e) { log('installAddMessageRewrite threw: ' + (e && e.message)); }

        if (okShowText && okDelegation && okAddMessage) {
          window.__rendererFix.installed = true;
          log('installed (show_text cache, word-action delegation, addMessage scroll/text-original rewrite)');
        } else {
          log('partial install (show_text=' + okShowText + ' delegation=' + okDelegation +
            ' addMessage=' + okAddMessage + '); will retry');
        }
      }

      tryInstall();

      if (!window.__rendererFix.installed) {
        var retryDelaysMs = [500, 1500, 3000, 6000, 10000];
        for (var r = 0; r < retryDelaysMs.length; r++) {
          (function (delay) {
            setTimeout(function () {
              if (!window.__rendererFix.installed) tryInstall();
            }, delay);
          })(retryDelaysMs[r]);
        }
      }
    })();
""".trimIndent()

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
        // Chromium picks the Android AudioSource from the processing constraints, so
        // "which microphone" and "which DSP" are the same knob:
        //
        //   ec:true                     -> VOICE_COMMUNICATION, which
        //                                  AudioManager.setCommunicationDevice governs, so
        //                                  MicRouter's USB selection applies. ns/agc detach
        //                                  independently, which is what 'hybrid' exploits.
        //   ec:false ns:false agc:false -> an unprocessed source; measured on a Pixel 9a
        //                                  this is CAMCORDER, pinned to a built-in mic
        //                                  array, which ignores setCommunicationDevice
        //
        // Only the shell knows which side of that trade is right, because only the shell
        // knows what is plugged in -- hence the AndroidMic bridge (see MicBridge.kt). The
        // mode is pulled inside the wrapper rather than read once at arm time, so it
        // reflects the routing at the moment recording actually starts.
        //
        // Outside the app -- the Playwright harnesses in tools/, a desktop browser --
        // window.AndroidMic does not exist and the mode falls back to 'raw', which is what
        // those harnesses assert.
        //
        // Confirm from logcat which mode was applied, and what the platform then did:
        //   [app-tweaks] capture mode=voice ec=true ns=undefined agc=undefined
        //   Recording active: source=VOICE_COMMUNICATION via USB-Audio - KM_B2 ...
        function shellCaptureMode() {
            try {
                if (window.AndroidMic && window.AndroidMic.captureMode) {
                    var m = String(window.AndroidMic.captureMode());
                    // The three literals CaptureModes.ALL holds. They cross a language
                    // boundary, so they cannot share a constant -- CaptureModesTest
                    // asserts the Kotlin side still matches these.
                    if (m === 'raw' || m === 'voice' || m === 'hybrid') return m;
                }
            } catch (e) {
                // Bridge absent (a desktop browser) or threw; fall through to raw.
            }
            return 'raw';
        }

        function applyCaptureMode() {
            if (window.__appCapturePatched) return;
            var md = navigator.mediaDevices;
            if (!md || !md.getUserMedia) return;
            window.__appCapturePatched = true;
            var orig = md.getUserMedia.bind(md);
            md.getUserMedia = function (constraints) {
                // Copy rather than mutate: the page may reuse its constraints object, and
                // handing back a modified one is a surprise it never asked for.
                //
                // The site passes { audio: true, video: false, echoCancellation: false } --
                // echoCancellation at the TOP level, where the browser ignores it. So the
                // site's own attempt at raw capture has never done anything, and `audio`
                // arrives as a boolean that has to be normalised to an object first.
                if (constraints && constraints.audio) {
                    var mode = shellCaptureMode();
                    var audio = (typeof constraints.audio === 'object')
                        ? Object.assign({}, constraints.audio)
                        : {};
                    if (mode === 'raw') {
                        audio.echoCancellation = false;
                        audio.noiseSuppression = false;
                        audio.autoGainControl = false;
                    } else if (mode === 'hybrid') {
                        // echoCancellation is what selects the source, while
                        // noiseSuppression and autoGainControl only control effect
                        // instances attached to the session -- measured on a Pixel 9a
                        // with the KM_B2 on 2026-08-30. So this is USB routing WITHOUT
                        // the noise suppression and AGC that ruin a lecture recording,
                        // and it is the automatic default when a USB device is routed.
                        audio.echoCancellation = true;
                        audio.noiseSuppression = false;
                        audio.autoGainControl = false;
                    } else {
                        // voice: ask for the communication path and leave the platform's
                        // voice DSP alone. Deliberately delete rather than set false --
                        // the page must not inherit a stale value from its own object.
                        audio.echoCancellation = true;
                        delete audio.noiseSuppression;
                        delete audio.autoGainControl;
                    }
                    constraints = Object.assign({}, constraints);
                    constraints.audio = audio;
                    console.log('[app-tweaks] capture mode=' + mode
                        + ' ec=' + audio.echoCancellation
                        + ' ns=' + audio.noiseSuppression
                        + ' agc=' + audio.autoGainControl);
                }
                return orig(constraints);
            };
            console.log('[app-tweaks] getUserMedia patched');
        }
        // The page's own globals appear only once their <script> has run, which is after
        // our first injection. Poll briefly rather than relying on injection timing.
        function whenDefined(test, apply, label) {
            if (test()) { apply(); return; }
            var tries = 0;
            var timer = setInterval(function () {
                if (test()) { apply(); clearInterval(timer); return; }
                if (++tries > 120) {
                    clearInterval(timer);
                    console.log('[app-tweaks] gave up waiting for ' + label);
                }
            }, 250);
        }

        // One wrapper over AudioContext doing two jobs, because both must be decided at
        // construction time and there is only one constructor to hook.
        //
        // 1. RESUME. The page builds its AudioContext from a load-time recording() call --
        //    see site-reference/present/inline/05-audio-capture-and-upload.js, where
        //    `new AudioContext()` runs BEFORE its own getUserMedia and nothing ever calls
        //    resume(). Under Chromium's autoplay policy a context constructed at that
        //    point starts SUSPENDED, and a suspended context never fires onaudioprocess:
        //    no audio encoded, nothing uploaded, empty transcript, no error. The record
        //    button still flips to "stop", so the UI looks like it is recording.
        //
        // 2. RATE. The page captures at the hardware rate and then resamples 48k -> 16k on
        //    the MAIN THREAD, inside the ScriptProcessorNode callback, costing 31ms of a
        //    341ms budget (wave-resampler defaults to cubic PLUS a 16th-order IIR
        //    low-pass, forward and backward over the buffer). Asking for a 16 kHz context
        //    makes Chromium resample natively, in the audio pipeline, off the main thread.
        //
        // ONLY the capture context is retuned. AudioQueuePlayer constructs one AudioContext
        // per TTS player for PLAYBACK; forcing 16 kHz on those would degrade TTS output.
        // Which context IS the capture one is decided semantically, by the constructing
        // call stack (see isCaptureConstruction() below) -- not by construction order.
        // tools/site-patches-test.mjs asserts that the capture context is still the one
        // retuned to 16 kHz under this rule.
        function patchAudioContext() {
            if (window.__appAudioPatched) return;
            var Native = window.AudioContext || window.webkitAudioContext;
            if (!Native) return;
            window.__appAudioPatched = true;
            window.__appAllCtxRates = [];
            var CAPTURE_RATE = 16000;
            // 4096 frames at 16 kHz = 256ms per callback, slightly tighter than the 341ms
            // the page gets today from 16384 frames at 48 kHz. Leaving the page's hardcoded
            // 16384 in place would make it 1024ms and triple capture latency.
            //
            // This does not affect upload throughput: the sender drains everything
            // accumulated on each send, so a slower link produces larger payloads rather
            // than more requests. Buffer size sets granularity and latency only.
            var CAPTURE_BUFFER = 4096;
            var live = [];

            // Which context is the CAPTURE one. This used to be "the first one the page
            // constructs", which is positional, not semantic -- and finding 2c
            // (docs/superpowers/specs/2026-09-01-device-log-findings.md) measured it
            // misfiring on the archive/read path, where the page never records and the
            // first context is therefore always an AudioQueuePlayer TTS context that got
            // forced to 16 kHz.
            //
            // The site constructs the capture context on the first line of its top-level
            // createAudioContext() (present/index.html:468) and every TTS context in the
            // AudioQueuePlayer constructor, so the constructing call stack separates them.
            //
            // NOT gated on "has getUserMedia been called": createAudioContext() builds the
            // context BEFORE it calls getUserMedia, so that flag is always false here.
            //
            // The stack is tested and thrown away, never logged -- its frame URLs contain
            // the session id.
            function isCaptureConstruction() {
                var stack = null;
                try { stack = new Error().stack; } catch (e) { stack = null; }
                if (stack) return /\bcreateAudioContext\b/.test(stack);
                // A JS engine with no Error.stack: fall back to the old positional rule,
                // but only on a page that HAS a capture path at all. That keeps the
                // archive page -- the case that actually broke -- correct either way.
                return window.__appAllCtxRates.length === 0
                    && typeof window.createAudioContext === 'function';
            }

            function kick(ctx, why) {
                if (!ctx || ctx.state !== 'suspended') return;
                var p = ctx.resume();
                if (p && p.then) {
                    p.then(function () {
                        console.log('[app-tweaks] AudioContext resumed (' + why + ')');
                    }, function (e) {
                        console.log('[app-tweaks] AudioContext resume failed (' + why + '): ' + e);
                    });
                }
            }

            var Wrapped = function (options) {
                var isCapture = isCaptureConstruction();
                var opts = options;
                if (isCapture) {
                    opts = {};
                    if (options) {
                        for (var k in options) {
                            if (Object.prototype.hasOwnProperty.call(options, k)) opts[k] = options[k];
                        }
                    }
                    opts.sampleRate = CAPTURE_RATE;
                }
                var ctx;
                try {
                    ctx = new Native(opts);
                } catch (e) {
                    // A device that cannot render at 16 kHz: fall back rather than lose
                    // capture entirely. The page's own resampler then does its usual work.
                    console.log('[app-tweaks] 16 kHz AudioContext refused (' + e
                        + '), falling back to the default rate');
                    ctx = new Native(options);
                }
                window.__appAllCtxRates.push(ctx.sampleRate);
                if (isCapture) window.__appCaptureCtx = ctx;
                live.push(ctx);
                console.log('[app-tweaks] AudioContext ' + window.__appAllCtxRates.length
                    + ' created, state=' + ctx.state + ' rate=' + ctx.sampleRate
                    + (isCapture ? ' (capture)' : ' (playback)'));
                kick(ctx, 'on create');
                ctx.addEventListener('statechange', function () {
                    console.log('[app-tweaks] AudioContext state -> ' + ctx.state);
                    if (ctx.state === 'suspended') kick(ctx, 'statechange');
                });
                return ctx;
            };
            Wrapped.prototype = Native.prototype;
            window.AudioContext = Wrapped;
            if (window.webkitAudioContext) window.webkitAudioContext = Wrapped;

            // The page hardcodes 16384 frames, sized for 48 kHz. Re-size only on the
            // capture context, and only when it actually came up at 16 kHz.
            try {
                var csp = Native.prototype.createScriptProcessor;
                Native.prototype.createScriptProcessor = function (bufferSize) {
                    if (this === window.__appCaptureCtx && this.sampleRate === CAPTURE_RATE
                        && bufferSize > CAPTURE_BUFFER) {
                        console.log('[app-tweaks] ScriptProcessor buffer ' + bufferSize
                            + ' -> ' + CAPTURE_BUFFER + ' ('
                            + Math.round(CAPTURE_BUFFER / CAPTURE_RATE * 1000) + 'ms at '
                            + CAPTURE_RATE + 'Hz)');
                        return csp.call(this, CAPTURE_BUFFER,
                            arguments.length > 1 ? arguments[1] : 1,
                            arguments.length > 2 ? arguments[2] : 1);
                    }
                    return csp.apply(this, arguments);
                };
            } catch (e) {
                console.log('[app-tweaks] cannot re-size ScriptProcessor buffer: ' + e);
            }

            // Last resort for the resume half: the first real user interaction always
            // carries activation, so even a policy we cannot relax from the shell gets
            // satisfied by one tap.
            ['pointerdown', 'touchend', 'keydown'].forEach(function (evt) {
                document.addEventListener(evt, function () {
                    live.forEach(function (c) { kick(c, evt); });
                }, { capture: true, passive: true });
            });
            console.log('[app-tweaks] AudioContext patched (resume + ' + CAPTURE_RATE + 'Hz capture)');
        }

        // wave-resampler runs its IIR low-pass even when the input and output rates are
        // equal, so a 16 kHz capture context alone does not make the page's call free.
        // Short-circuit that case. Everything else is left to the real implementation, so
        // a fallback to a non-16 kHz context still resamples correctly.
        function shortCircuitResampler() {
            var wr = window.waveResampler;
            if (!wr || typeof wr.resample !== 'function') return;
            var orig = wr.resample;
            wr.resample = function (samples, fromRate, toRate) {
                if (fromRate === toRate) return samples;
                return orig.apply(this, arguments);
            };
            console.log('[app-tweaks] waveResampler.resample short-circuited for equal rates');
        }

        // The site issues SYNCHRONOUS XMLHttpRequests -- request.open(..., false) -- from
        // five call sites (present/index.html:2947, 2993, 3021, 5857, 5887). Each freezes
        // the main thread for a full round trip: measured at ~90ms of SERVER time on good
        // WiFi, plus 150-250ms RTT on this app's normal link. Page load serialized ~36 of
        // them for 3.3s.
        //
        // Two of the three endpoints are immutable for the lifetime of a session -- they
        // describe the pipeline topology and its language mapping, neither of which
        // changes while a lecture runs -- so a repeat call can be answered from memory.
        //
        // /get_previous_messages is NOT one of them: it is range-dependent and carries
        // lecture content. It is deliberately absent from this list and must stay absent.
        var IMMUTABLE_SYNC_PATHS = ['/getgraph', '/get_output_language_component'];

        function isImmutableSyncPath(url) {
            var path = String(url).split('?')[0];
            for (var i = 0; i < IMMUTABLE_SYNC_PATHS.length; i++) {
                if (path.indexOf(IMMUTABLE_SYNC_PATHS[i]) >= 0) return true;
            }
            return false;
        }

        // A THIRD copy of redactIds -- NET_TRACE_JS:170 and PERF_TRACE_JS:357 have the
        // other two, and :355 already documents why they are not shared. The reason for
        // this one is different and stronger: both of those are injected in DEBUG builds
        // only, while SITE_TWEAKS_JS ships in release. The session id is a long hex path
        // SEGMENT, not a query param, so stripping query strings would not keep it out of
        // a log export. Every path this patch logs goes through here.
        function redactPath(url) {
            var p;
            try { p = new URL(String(url), location.href).pathname; }
            catch (e) { p = String(url).split('?')[0]; }
            return p.replace(/\/[0-9a-f]{20,}/gi, '/<id>');
        }

        // The prefetch writes into the same store the shim reads, so both MUST build the
        // key the same way. Body included: get_output_language_component is one path
        // serving many workers, discriminated only by its body.
        function cacheKey(method, url, body) {
            return String(method) + ' ' + String(url).split('?')[0]
                + ' ' + (body == null ? '' : String(body));
        }

        function syncXhrCache() {
            if (window.__appSyncXhrPatched) return;
            window.__appSyncXhrPatched = true;
            var store = window.__appSyncXhrStore = window.__appSyncXhrStore || {};
            var stats = window.__appSyncXhrStats = window.__appSyncXhrStats
                || { hit: 0, miss: 0, stored: 0, passthrough: 0, refused: 0 };
            var origOpen = XMLHttpRequest.prototype.open;
            var origSend = XMLHttpRequest.prototype.send;
            var SHADOWED = ['readyState', 'status', 'responseText'];
            // A lecture is an hour long, so per-call logging has to be bounded. HITs are
            // sampled then counted; MISS and STORE are always logged because there is at
            // most one of each per distinct key, and they are exactly the lines you need
            // when the cache is not working. See the spec's "Noise control".
            var HIT_LOG_CAP = 5;
            // ~90ms of server time per avoided round trip, measured on device 2026-08-30
            // on good WiFi. An estimate for the log line, not a measurement.
            var SAVED_MS_EACH = 90;

            XMLHttpRequest.prototype.open = function (method, url, async) {
                // updateWindowContent (index.html:2988-3008) creates ONE XMLHttpRequest
                // and reuses it across every worker in the graph. A previous cache hit
                // left own data properties on this object shadowing the prototype
                // accessors; if they survive into the next request, its real response is
                // read through them. Clear them on every open().
                for (var i = 0; i < SHADOWED.length; i++) {
                    if (Object.prototype.hasOwnProperty.call(this, SHADOWED[i])) {
                        delete this[SHADOWED[i]];
                    }
                }
                this.__appSync = (async === false);
                this.__appCacheable = this.__appSync && isImmutableSyncPath(url);
                this.__appMethod = method;
                this.__appUrl = url;
                return origOpen.apply(this, arguments);
            };

            XMLHttpRequest.prototype.send = function (body) {
                if (!this.__appCacheable) {
                    // Every /get_previous_messages lands here. Counted, never logged
                    // individually: there are hundreds an hour and their bodies and
                    // responses are lecture content.
                    if (this.__appSync) stats.passthrough++;
                    return origSend.apply(this, arguments);
                }
                var key = cacheKey(this.__appMethod, this.__appUrl, body);
                var shown = String(this.__appMethod) + ' ' + redactPath(this.__appUrl)
                    + (body == null ? '' : ' body=' + String(body).slice(0, 80));
                if (Object.prototype.hasOwnProperty.call(store, key)) {
                    // Own data properties shadow the prototype's accessors, so the
                    // synchronous caller reads a complete 200 without a round trip.
                    Object.defineProperty(this, 'readyState',
                        { value: 4, configurable: true });
                    Object.defineProperty(this, 'status',
                        { value: 200, configurable: true });
                    Object.defineProperty(this, 'responseText',
                        { value: store[key], configurable: true });
                    stats.hit++;
                    if (stats.hit <= HIT_LOG_CAP) {
                        console.log('[app-tweaks] sync-xhr HIT  ' + shown);
                    }
                    return;
                }
                var result = origSend.apply(this, arguments);
                stats.miss++;
                // Always logged, and it prints the exact key that was looked up. A MISS
                // on an immutable path AFTER the prefetch reported keys stored is the
                // signature of a key mismatch between the two sides -- the most likely
                // bug in this patch -- and this line plus the STORE line below are what
                // let you diff the two keys from logcat alone.
                console.log('[app-tweaks] sync-xhr MISS ' + shown);
                // Only a 200 is stored. A cached failure would be pinned for the whole
                // lecture -- and getGraph() falls back to the internal ltapi:5000 host on
                // non-200, which does not resolve off-site and hangs.
                try {
                    if (this.status === 200) {
                        store[key] = this.responseText;
                        stats.stored++;
                        console.log('[app-tweaks] sync-xhr STORE ' + shown
                            + ' (200, ' + String(this.responseText || '').length + 'B)');
                    } else {
                        stats.refused++;
                        console.log('[app-tweaks] sync-xhr NOT CACHED ' + shown
                            + ' (status ' + this.status + ')');
                    }
                } catch (e) {
                    // A cross-origin or errored request can throw on responseText; a miss
                    // that stores nothing is the correct outcome.
                    stats.refused++;
                    console.log('[app-tweaks] sync-xhr NOT CACHED ' + shown
                        + ' (threw: ' + e + ')');
                }
                return result;
            };

            function summary(why) {
                console.log('[app-tweaks] sync-xhr summary (' + why + '): hit=' + stats.hit
                    + ' miss=' + stats.miss + ' stored=' + stats.stored
                    + ' refused=' + stats.refused
                    + ' passthrough=' + stats.passthrough
                    + ' savedApprox=' + (stats.hit * SAVED_MS_EACH) + 'ms');
            }
            // One line after the load burst has settled, and one on the way out, so a
            // logcat export taken at any point in a lecture has a total in it.
            setTimeout(function () { summary('12s'); }, 12000);
            window.addEventListener('pagehide', function () { summary('pagehide'); });

            console.log('[app-tweaks] sync-xhr cache armed');
        }

        // The session id: window.sessionId is set in <head> (index.html:19,
        // `window.sessionId = "2716...477"`), before any body script runs. Prefer it --
        // it's exact and cheap. Fall back to scanning <script> text for a literal
        // /webapi/<id>/ path (present on the vendored snapshot at index.html:415 etc.) for
        // a page that never sets the global. Scan only <script> text, not the whole
        // document, so the fallback stays cheap too.
        function webapiBase() {
            if (window.sessionId) return '/webapi/' + window.sessionId;
            var scripts = document.getElementsByTagName('script');
            for (var i = 0; i < scripts.length; i++) {
                var m = (scripts[i].textContent || '')
                    .match(/["']\/webapi\/([0-9a-zA-Z]{16,})\//);
                if (m) return '/webapi/' + m[1];
            }
            return null;
        }

        // Warm the cache syncXhrCache() reads, before the page's synchronous calls run.
        // Entirely best-effort: on any failure the page falls back to exactly today's
        // behaviour, which is the same synchronous call it makes now.
        //
        // Uses fetch, not XHR, so these do not go through the shim's own wrapper and
        // cannot recurse. Same-origin credentials are the fetch default, which is what the
        // site's Dex session cookie needs -- do not "tidy" that to omit.
        function prefetchImmutable() {
            if (window.__appPrefetched) return;
            var base = webapiBase();
            if (!base) {
                console.log('[app-tweaks] prefetch skipped: no session id yet');
                return;
            }
            window.__appPrefetched = true;
            var store = window.__appSyncXhrStore = window.__appSyncXhrStore || {};
            var t0 = performance.now();
            // Counts what post() actually DID, so the summary cannot report a skip as a
            // fetch. Capture B logged "15 ok, 0 failed in 0ms" for 15 skips; the 0ms was
            // the only clue. See the 2026-09-01 findings, finding 2a.
            var outcomes = { fetched: 0, skipped: 0 };
            var graphUrl = base + '/getgraph';
            // redactPath() so the session id never reaches a log export, exactly as in
            // syncXhrCache. This line is how you tell "the prefetch never ran" apart from
            // "it ran against the wrong base path".
            console.log('[app-tweaks] sync-xhr base=' + redactPath(base)
                + ' from ' + (window.sessionId ? 'window.sessionId' : 'page scripts'));

            function post(url, body, label, trackWin) {
                // The page's own synchronous XHR for this same key can still win the
                // race -- e.g. on a page whose sessionId assignment is caught only by
                // the fallback poll below, or simply a slower network for THIS
                // particular job. If it already stored a response, fetching again here
                // would be a second, redundant hit to the network for an endpoint that
                // is supposed to reach it AT MOST once. Read the store first and
                // short-circuit on whatever is already there, success or not: this
                // function only ever races the page's own cacheable calls, and those
                // only store on a 200 (see syncXhrCache's STORE branch), so anything
                // found here is already a good response.
                var key = cacheKey('POST', url, body);
                if (Object.prototype.hasOwnProperty.call(store, key)) {
                    outcomes.skipped++;
                    console.log('[app-tweaks] prefetch ' + label
                        + ' already cached (page won the race), skipping fetch');
                    return Promise.resolve(store[key]);
                }
                if (trackWin) {
                    // Recorded HERE, at the instant the store-check found nothing --
                    // not after the fetch resolves. Winning the race this task exists
                    // to win is about being FIRST TO ASK, not about resolving fastest;
                    // tools/site-patches-test.mjs reads this flag to tell "the prefetch
                    // actually reached the network ahead of the page" apart from "the
                    // prefetch never got the chance to try".
                    window.__appPrefetchWon = true;
                }
                outcomes.fetched++;
                var t = performance.now();
                return fetch(url, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json;charset=UTF-8' },
                    body: body
                }).then(function (r) {
                    if (r.status !== 200) throw new Error('HTTP ' + r.status);
                    return r.text();
                }).then(function (text) {
                    store[cacheKey('POST', url, body)] = text;
                    console.log('[app-tweaks] prefetch ' + label + ' ok in '
                        + Math.round(performance.now() - t) + 'ms, ' + text.length + 'B');
                    return text;
                }, function (e) {
                    // Logged per job, not just in aggregate: one language failing while
                    // the rest succeed is a different problem from all of them failing,
                    // and only the per-job line separates them.
                    console.log('[app-tweaks] prefetch ' + label + ' FAILED after '
                        + Math.round(performance.now() - t) + 'ms: ' + e);
                    throw e;
                });
            }

            // sendApiRequest calls request.send() with NO argument, so the key the shim
            // computes has an empty body segment. Pass undefined here to match it.
            // trackWin=true: getgraph is the endpoint the page's OWN first synchronous
            // call always races against (it fires during body parsing, before anything
            // else the page does), so this is the one call whose network-vs-skipped
            // outcome actually answers "did the prefetch win".
            post(graphUrl, undefined, 'getgraph', true).then(function (text) {
                var graph = JSON.parse(text);
                var jobs = [];
                var workers = 0;
                // Snapshot after getgraph's own post() has counted itself and before any
                // language job is created, so the numbers below describe the language jobs
                // alone. getgraph reports its own outcome on its own line.
                var langBase = { fetched: outcomes.fetched, skipped: outcomes.skipped };
                for (var worker in graph) {
                    if (!Object.prototype.hasOwnProperty.call(graph, worker)) continue;
                    workers++;
                    var downstream = graph[worker];
                    // Mirrors updateWindowContent's own filter, index.html:2990.
                    if (!downstream || String(downstream).indexOf('api') < 0) continue;
                    var stream = worker.split(':')[1];
                    if (!stream) continue;
                    // The site double-encodes: JSON.stringify(JSON.stringify(obj)) -- a
                    // JSON string CONTAINING a JSON string (index.html:2995). The key must
                    // match byte for byte, so build the body identically.
                    jobs.push(post(
                        base + '/' + stream + '/get_output_language_component',
                        JSON.stringify(JSON.stringify({ component: worker })),
                        'lang ' + worker
                    ).then(function () { return true; }, function () { return false; }));
                }
                // workers vs jobs.length is the check that the graph parsed into what this
                // expects: if a shape change makes the 'api' filter match nothing, jobs is
                // 0 while workers is not, and only this line shows it.
                console.log('[app-tweaks] prefetch getgraph parsed: ' + workers
                    + ' workers, ' + jobs.length + ' with api');
                var t1 = performance.now();
                return Promise.all(jobs).then(function (results) {
                    var ok = 0;
                    for (var i = 0; i < results.length; i++) if (results[i]) ok++;
                    console.log('[app-tweaks] prefetch done: ' + ok + ' ok ('
                        + (outcomes.fetched - langBase.fetched) + ' fetched, '
                        + (outcomes.skipped - langBase.skipped) + ' already cached), '
                        + (results.length - ok) + ' failed in '
                        + Math.round(performance.now() - t1) + 'ms, '
                        + Object.keys(store).length + ' keys stored');
                });
            }).catch(function (e) {
                console.log('[app-tweaks] prefetch getgraph failed: ' + e
                    + ' (page falls back to its own synchronous call)');
            });
        }

        // Before killOverlays and outside setup(): these need no document.body, and they
        // have to be in place before the user presses record -- and, for the AudioContext
        // patch, before the page's own load-time recording() call -- not merely before
        // load ends.
        patchAudioContext();
        // Must be in place before the site's own scripts run -- the first synchronous
        // getgraph happens during body parsing, via showWindow -> updateWindowContent
        // (index.html:1223) -- not merely before load ends.
        syncXhrCache();
        // Guard hoisted out of shortCircuitResampler and up to here: SITE_TWEAKS_JS is
        // injected four times per page load, and if waveResampler never loads (CDN
        // unreachable), each injection would otherwise start its own 250ms x 120 polling
        // interval. Setting the flag before the wait starts -- not only once the patch
        // lands -- makes every injection after the first a no-op.
        if (!window.__appResamplerPatched) {
            window.__appResamplerPatched = true;
            whenDefined(
                function () { return !!(window.waveResampler && window.waveResampler.resample); },
                shortCircuitResampler,
                'waveResampler');
        }
        // MUST be armed here, in the pre-setup block, NOT from inside setup(). setup() is
        // armed on DOMContentLoaded, but the page's OWN first synchronous getgraph fires
        // during body parsing -- showWindow() -> updateWindowContent() -> getGraph(),
        // documented at site-reference/present/index.html:18-19 -- which is BEFORE
        // DOMContentLoaded. A prefetch that waited for setup() would lose that race every
        // time and just warm a cache nobody reads.
        //
        // A POLL for window.sessionId loses this race too, and did in an earlier version
        // of this patch: window.sessionId is assigned at index.html:17, in a <head>
        // script, before body parsing starts, but whenDefined's first REAL check (after
        // its immediate, too-early one) is its first setInterval tick -- 250ms later. On
        // the vendored snapshot the page's own first synchronous getgraph completes well
        // inside that 250ms, so a poll-driven prefetch always arrived after the page had
        // already made (and cached) that first call itself. The test still read
        // stats.hit > 0 as success, but that hit came from Task 2's memoization of the
        // page's OWN second getgraph call, not from this prefetch beating anything --
        // measured and reasoned through on 2026-08-31 (task-3-report.md, "Fix round 1").
        //
        // Fix: hook the ASSIGNMENT itself with an accessor, so the prefetch fires
        // SYNCHRONOUSLY in the same tick as `window.sessionId = "..."` runs in <head> --
        // before ANY body script, the getgraph call included, has had a chance to run.
        if (!window.__appPrefetchArmed) {
            window.__appPrefetchArmed = true;
            var sessionIdDesc = Object.getOwnPropertyDescriptor(window, 'sessionId');
            if (sessionIdDesc && Object.prototype.hasOwnProperty.call(sessionIdDesc, 'value')) {
                // Already a plain value: the page's <head> script ran before this
                // injection landed (SITE_TWEAKS_JS runs from onPageStarted, which is not
                // guaranteed to win against <head>, only likely to). No accessor needed
                // -- just use what's already there.
                prefetchImmutable();
            } else {
                var sessionIdBacking;
                try {
                    Object.defineProperty(window, 'sessionId', {
                        configurable: true,
                        get: function () { return sessionIdBacking; },
                        set: function (v) {
                            // Store first, so webapiBase() (called from inside
                            // prefetchImmutable, synchronously, below) reads a page that
                            // already looks like it has a sessionId -- exactly as it would
                            // with no accessor here at all. The page's own later reads of
                            // window.sessionId keep working the same way, through this same
                            // getter: transparent from its point of view.
                            sessionIdBacking = v;
                            prefetchImmutable();
                        }
                    });
                } catch (e) {
                    // Some future page could make window.sessionId non-configurable, or
                    // otherwise refuse this. __appPrefetchArmed is already latched above,
                    // so there is no retry of this block -- but the fallback poll right
                    // below still catches the value once the page assigns it, just later.
                    console.log('[app-tweaks] sessionId accessor refused (' + e
                        + '), prefetch falls back to polling');
                }
            }
            // Fallback for a page that never assigns window.sessionId at all -- only the
            // <script>-text scan in webapiBase() can find a session id then. Belt and
            // braces: prefetchImmutable() is idempotent (window.__appPrefetched), so this
            // cannot double-fire if the accessor above already won the race.
            whenDefined(
                function () { return !!webapiBase(); },
                prefetchImmutable,
                'session id (prefetch, fallback poll)');
        }
        applyCaptureMode();
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

// Whether the system is currently in night mode, read live off the WebView's context.
// Used for one-shot decisions (the error page's background); the WebView's own day/night
// state follows isSystemInDarkTheme() instead, which recomposes when the theme changes
// without the activity restarting.
private fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

// The manifest's VIEW intent-filter matches the whole lt2srv.iar.kit.edu host (short
// links, /present/<id>, ...), but only a VIEW intent actually carries a link to open --
// the launcher MAIN intent has no data at all. Anything else falls back to SITE_URL.
private fun launchUrl(intent: Intent?): String {
    val data = intent?.data ?: return SITE_URL
    if (data.scheme != "http" && data.scheme != "https") return SITE_URL
    if (data.host != Uri.parse(SITE_URL).host) return SITE_URL
    return data.toString()
}

class MainActivity : ComponentActivity() {
    private lateinit var micRouter: MicRouter
    private lateinit var micDiagnostics: MicDiagnostics
    private lateinit var micBridge: MicBridge
    private lateinit var captureModePreference: CaptureModePreference

    // Held while the OS permission dialog is up, so the page's own permission request can
    // be answered once the user has decided. Null at all other times.
    private var pendingWebPermission: PermissionRequest? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(LOG_TAG, "RECORD_AUDIO granted=$granted")
            val pending = pendingWebPermission
            pendingWebPermission = null
            if (pending == null) {
                // This launcher is shared between two callers: the startup request in
                // onCreate (pendingWebPermission null, nothing to grant/deny) and
                // handleWebAudioPermission's page-triggered request (pendingWebPermission
                // set). Chained off this result rather than launched beside it: two
                // launch() calls in one frame can drop one. But that reasoning only holds
                // for the startup path -- popping a second system dialog for
                // POST_NOTIFICATIONS is fine before the page has asked for anything, and
                // wrong the moment it's actually trying to start a recording, since the
                // notification dialog would sit on top of the page while
                // pending.grant() lets getUserMedia resolve and capture go live behind it.
                // So this only runs on the startup path, where pending is null.
                askForNotificationPermissionIfNeeded()
                return@registerForActivityResult
            }
            if (granted) {
                pending.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                pending.deny()
            }
        }

    // Requested only so the foreground service's ongoing notification is visible. The
    // service runs either way; nothing is gated on the result, which is why it is not
    // logged as a failure.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(LOG_TAG, "POST_NOTIFICATIONS granted=$granted")
        }

    private fun askForNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Diagnostics first, so the device inventory is logged before any routing decision.
        micDiagnostics = MicDiagnostics(this, ::onCaptureActiveChanged, ::onCaptureSilencedChanged)
        micDiagnostics.attach()
        micRouter = MicRouter(this)
        micRouter.attach()
        captureModePreference = CaptureModePreference(this)
        // Debug builds only. `adb shell am start -n org.Craeckie.lecturerecorder/.MainActivity
        // --es micmode voice` pins the capture mode for the A/B/C probe in Task 4 of
        // docs/superpowers/plans/2026-08-30-capture-mode-resolution.md. Release builds
        // ignore it: MainActivity is an exported launcher activity, so another app must not
        // be able to choose this app's audio mode.
        //
        // The extra writes THROUGH the preference rather than around it, so there is a
        // single source of truth: the in-app selector then shows what adb asked for, and
        // the choice survives the next launch the same way a tapped one does.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val extra = CaptureModes.sanitize(intent?.getStringExtra("micmode"))
            if (extra != null) {
                Log.i(LOG_TAG, "Capture mode forced to '$extra' by intent extra")
                captureModePreference.override = extra
            }
        }
        // A supplier, not a value: the selector can change this between recordings without
        // the page reloading, and the next getUserMedia must see the new choice.
        micBridge = MicBridge(micRouter) { captureModePreference.override }
        // Asked before the page loads, so the OS dialog doesn't land on top of the site's
        // own recording UI mid-lecture.
        if (!hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            // No RECORD_AUDIO dialog is coming, so nothing to chain off.
            askForNotificationPermissionIfNeeded()
        }
        setContent {
            AppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        SiteWebView(
                            startUrl = launchUrl(intent),
                            onAudioPermissionRequest = ::handleWebAudioPermission,
                            micBridge = micBridge,
                            captureActive = captureActive,
                            onExitApp = ::finish,
                        )
                        CaptureModeSelector(
                            modifier = Modifier.align(Alignment.BottomStart),
                            initialOverride = captureModePreference.override,
                            onOverrideChange = { captureModePreference.override = it },
                        )
                        CaptureSilencedBanner(
                            visible = captureSilenced,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
    }

    // Mirrors MicDiagnostics' capture signal into Compose, so the back handler can ask
    // before throwing away a live recording. This is the shell's ONLY knowledge that the
    // page is recording -- the site never tells us -- so it drives both the screen flag
    // and the confirmation.
    private var captureActive by mutableStateOf(false)

    private fun onCaptureActiveChanged(active: Boolean) {
        captureActive = active
        setKeepScreenOn(active)
        when (CaptureServiceControl.next(active, captureServiceRunning)) {
            CaptureServiceControl.Action.START -> {
                // start() never throws -- a failure to actually start is reported through
                // the return value, not an exception, precisely so a transient platform
                // refusal degrades to today's digital-silence behaviour instead of crashing
                // the activity and ending the recording outright. Recording the real outcome
                // here (rather than assuming true) is what lets the next STOP transition
                // correctly become a no-op instead of stopping a service that isn't running.
                captureServiceRunning = CaptureForegroundService.start(this)
            }
            CaptureServiceControl.Action.STOP -> {
                CaptureForegroundService.stop(this)
                captureServiceRunning = false
            }
            CaptureServiceControl.Action.NONE -> Unit
        }
    }

    private var captureServiceRunning = false

    // Mirrors MicDiagnostics' silence signal into Compose. Deliberately NOT dismissible:
    // this is a live data-loss condition, not a notice, and the whole point is that the
    // page's own UI keeps looking like a healthy recording while it is true.
    private var captureSilenced by mutableStateOf(false)

    private fun onCaptureSilencedChanged(silenced: Boolean) {
        captureSilenced = silenced
    }

    // A screen that sleeps mid-lecture used to kill the recording silently: Android feeds a
    // backgrounded app with no microphone-type foreground service digital silence rather
    // than an error (86.5 s lost on 2026-09-01 — see the device-log findings). The service
    // above is the fix for that; this flag is still held on top of it, because staying awake
    // is the right default for a lecture and it also keeps the page's own SSE stream alive.
    // Held only while capture is actually live, so ordinary browsing still lets the screen
    // time out.
    private fun setKeepScreenOn(keepOn: Boolean) {
        Log.i(LOG_TAG, "FLAG_KEEP_SCREEN_ON ${if (keepOn) "set" else "cleared"}")
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    // The activity now handles uiMode itself instead of being recreated (a restart would
    // reload the page and delete the recording session). Compose picks the new theme up on
    // its own, and SiteWebView switches Dark Reader in place -- but the edge-to-edge system
    // bar styling was decided once in onCreate from the configuration at that moment, so it
    // has to be re-decided here or the status/navigation bar icons keep the old contrast.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }

    // Deliberately onDestroy and not onPause: the routing has to survive the screen going
    // off while a lecture is being recorded.
    override fun onDestroy() {
        // The page lives in this activity's WebView, so once the activity is gone there is
        // nothing left to record — the exemption must not outlive it.
        CaptureForegroundService.stop(this)
        captureServiceRunning = false
        micRouter.detach()
        micDiagnostics.detach()
        super.onDestroy()
    }
}

// The one condition the wrapped site cannot show, because it cannot see it: the platform is
// handing this app zeros and the page's recording UI looks entirely healthy. Full-width,
// error-coloured and not dismissible. The backgrounding hole that motivated building this
// (86.5 s lost on 2026-09-01) is now covered by CaptureForegroundService instead — and by
// definition the banner is off-screen while backgrounded anyway, clearing again the moment
// the user foregrounds and the silencing ends. What the banner is actually for is the cases
// the service cannot fix: the OS privacy mic toggle, an incoming call, or another app taking
// the microphone — all silent to the page, and all needing a human to notice and react while
// looking at the screen. See docs/superpowers/specs/2026-09-01-device-log-findings.md.
@Composable
fun CaptureSilencedBanner(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.capture_silenced_title),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.capture_silenced_body),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// The in-app capture-mode override: a deliberately tiny, mostly transparent chip in the
// bottom-left corner that opens a four-way picker.
//
// It sits ON TOP of the wrapped site, so it is sized and faded to be ignorable — the site
// owns the screen and this is a diagnostic control, not app chrome. The bottom-left corner
// is the one the site leaves empty; if that ever changes, move the chip rather than growing
// it.
//
// "Automatic" is the normal setting. The three named modes exist because which one actually
// reaches an attached USB microphone is an empirical question — see CaptureModePreference
// and the probe plan. Changing the mode takes effect on the NEXT recording, since the page
// only reads the bridge inside getUserMedia; it does not disturb a recording in progress.
@Composable
fun CaptureModeSelector(
    modifier: Modifier = Modifier,
    initialOverride: String?,
    onOverrideChange: (String?) -> Unit,
) {
    var override by remember { mutableStateOf(initialOverride) }
    var picking by remember { mutableStateOf(false) }

    Text(
        text = override?.uppercase() ?: "AUTO",
        fontSize = 9.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(4.dp)
            .alpha(0.35f)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp),
            )
            .clickable { picking = true }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )

    if (!picking) return
    AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text("Microphone capture mode") },
        text = {
            Column {
                CAPTURE_MODE_CHOICES.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = override == value,
                                onClick = {
                                    override = value
                                    onOverrideChange(value)
                                    picking = false
                                },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = override == value, onClick = null)
                        Text(text = label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { picking = false }) { Text("Close") }
        },
    )
}

// Ordered as the picker shows them. The labels say what each mode costs, because the
// trade-off (USB routing vs. the voice-call DSP that ruins a lecture recording) is the
// entire reason this control exists.
private val CAPTURE_MODE_CHOICES: List<Pair<String?, String>> = listOf(
    null to "Automatic — USB attached: hybrid, otherwise raw",
    CaptureModes.RAW to "raw — no DSP, built-in mic only",
    CaptureModes.VOICE to "voice — USB routing, voice-call DSP",
    CaptureModes.HYBRID to "hybrid — USB routing, no NS/AGC",
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteWebView(
    modifier: Modifier = Modifier,
    startUrl: String = SITE_URL,
    onAudioPermissionRequest: (PermissionRequest) -> Unit,
    micBridge: MicBridge,
    captureActive: Boolean = false,
    onExitApp: () -> Unit = {},
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    // canGoBack() is a plain method call, not Compose state, so it must be mirrored
    // into a State explicitly (updated on every navigation) for BackHandler to react
    // to in-page navigation instead of latching to the value from first composition.
    var canGoBack by remember { mutableStateOf(false) }
    var confirmingLeave by remember { mutableStateOf(false) }

    // Leaving the recording page is destructive and irreversible: the site POSTs
    // /delete_session/<id> as the page unloads, so the session and its transcript are gone
    // -- no history entry brings them back. Both back paths do it, which is why this
    // intercepts BOTH: goBack() when there is history, and finishing the activity when
    // there is not (the default when BackHandler is disabled).
    fun leave() {
        if (canGoBack) webView?.goBack() else onExitApp()
    }

    // Enabled whenever back would do something destructive OR navigable. While capture is
    // live it always asks first; otherwise back keeps walking the WebView history exactly
    // as before, and falls through to the system when there is none.
    BackHandler(enabled = captureActive || canGoBack) {
        if (captureActive) confirmingLeave = true else leave()
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text("Recording in progress") },
            text = {
                Text(
                    "Leaving this page ends the session on the server and discards the "
                        + "transcript. Stop the recording first if you want to keep it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLeave = false
                    Log.i(LOG_TAG, "Leaving a live recording, confirmed by the user")
                    leave()
                }) { Text("Discard and leave") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) { Text("Keep recording") }
            },
        )
    }

    // Day/night is LIVE state, not a per-WebView constant: uiMode is in
    // android:configChanges (a restart would reload the page and delete the recording
    // session), so a theme change now arrives here as a recomposition and has to be
    // applied to the page that is already loaded.
    val isDark = isSystemInDarkTheme()
    // Read inside the WebViewClient callbacks, which outlive this composition and must see
    // the CURRENT theme on every navigation, not the one from the factory call.
    val darkState = rememberUpdatedState(isDark)

    LaunchedEffect(webView, isDark) {
        val view = webView ?: return@LaunchedEffect
        view.setBackgroundColor(Color.parseColor(if (isDark) DARK_SURFACE else LIGHT_SURFACE))
        if (isDark) {
            view.evaluateJavascript(darkReaderInjectJs(view.context), null)
            view.evaluateJavascript(ENABLE_DARKREADER_JS, null)
        } else {
            view.evaluateJavascript(DISABLE_DARKREADER_JS, null)
        }
        Log.i(LOG_TAG, "Night mode=$isDark applied in place (no reload)")
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
                // Defaults to true, which blocks media from starting without a user
                // gesture -- including an AudioContext. The wrapped page builds its
                // AudioContext from a load-time recording() call, with no gesture
                // anywhere in the stack, and never calls resume(), so with the default
                // the context can stay suspended forever: no onaudioprocess, nothing
                // uploaded, an empty transcript and no error anywhere in the page.
                // PERF_TRACE_JS's ctx= field reports the state this actually produces.
                settings.mediaPlaybackRequiresUserGesture = false
                // The page's getUserMedia patch pulls the capture mode from here at call
                // time. One no-argument method returning one of three fixed strings --
                // see MicBridge for why the surface is this small.
                addJavascriptInterface(micBridge, "AndroidMic")
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
                        // Both halves, not just the source: the page console.logs URLs of
                        // its own, and a log export is shared off-device. The JS probes
                        // already redact their own lines, so this is a no-op for those.
                        Log.d(
                            LOG_TAG,
                            "${redactSessionIds(msg.message())} " +
                                "(${redactSourceUrl(msg.sourceId())}:${msg.lineNumber()})",
                        )
                        return true
                    }

                    // Without this override WebView denies every getUserMedia call, so the
                    // site's recorder silently never starts.
                    override fun onPermissionRequest(request: PermissionRequest) {
                        onAudioPermissionRequest(request)
                    }
                }
                // Avoids a white flash of the WebView's own surface before the page has
                // painted and Dark Reader has kicked in. Re-asserted by the theme effect
                // above whenever the system theme changes.
                if (darkState.value) setBackgroundColor(Color.parseColor(DARK_SURFACE))
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
                        // First, so fetch and WebSocket are wrapped before the site's own
                        // scripts run. Re-posted once for the same reason the tweaks are:
                        // this very first injection can land before the document exists.
                        if (isDebuggable) {
                            view.evaluateJavascript(NET_TRACE_JS, null)
                            // Before the site's own scripts run: PERF_TRACE_JS has to claim
                            // createScriptProcessor and EventSource ahead of the page.
                            view.evaluateJavascript(PERF_TRACE_JS, null)
                            view.postDelayed({ view.evaluateJavascript(NET_TRACE_JS, null) }, 2000)
                            view.postDelayed({ view.evaluateJavascript(PERF_TRACE_JS, null) }, 2000)
                        }
                        if (darkState.value) {
                            view.evaluateJavascript(darkReaderInjectJs(view.context), null)
                            view.evaluateJavascript(ENABLE_DARKREADER_JS, null)
                        }
                        // Inject early and re-post on timers (scheduled here, NOT in
                        // onPageFinished — the 'load' event can be stalled indefinitely
                        // by ad/consent scripts) in case this very first injection lands
                        // before the document exists at all. The script itself arms on
                        // DOMContentLoaded and is idempotent, so double-injection is safe.
                        view.evaluateJavascript(SITE_TWEAKS_JS, null)
                        view.evaluateJavascript(RENDERER_FIX_JS, null)
                        for (delayMs in longArrayOf(2000, 10000)) {
                            view.postDelayed({ view.evaluateJavascript(SITE_TWEAKS_JS, null) }, delayMs)
                            view.postDelayed({ view.evaluateJavascript(RENDERER_FIX_JS, null) }, delayMs)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (darkState.value) {
                            // Re-asserted after load in case late page scripts touched
                            // <head> after our first injection. The "if (!window.DarkReader)"
                            // guard keeps this from re-parsing the ~346KB bundle a second
                            // time within the same document.
                            view.evaluateJavascript(darkReaderInjectJs(view.context), null)
                            view.evaluateJavascript(ENABLE_DARKREADER_JS, null)
                        }
                        view.evaluateJavascript(SITE_TWEAKS_JS, null)
                        view.evaluateJavascript(RENDERER_FIX_JS, null)
                        canGoBack = view.canGoBack()
                    }
                }
                loadUrl(startUrl)
                webView = this
            }
        },
        onRelease = {
            webView?.destroy()
            webView = null
        },
    )
}
