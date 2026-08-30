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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
        // ONLY the first context is retuned. AudioQueuePlayer constructs one AudioContext
        // per TTS player for PLAYBACK; forcing 16 kHz on those would degrade TTS output.
        // The capture context is the first the page constructs, which
        // tools/site-patches-test.mjs asserts.
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
                var isFirst = (window.__appAllCtxRates.length === 0);
                var opts = options;
                if (isFirst) {
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
                if (isFirst) window.__appCaptureCtx = ctx;
                live.push(ctx);
                console.log('[app-tweaks] AudioContext ' + window.__appAllCtxRates.length
                    + ' created, state=' + ctx.state + ' rate=' + ctx.sampleRate
                    + (isFirst ? ' (capture)' : ' (playback)'));
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

        // Before killOverlays and outside setup(): these need no document.body, and they
        // have to be in place before the user presses record -- and, for the AudioContext
        // patch, before the page's own load-time recording() call -- not merely before
        // load ends.
        patchAudioContext();
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

// Whether the system is currently in night mode, read live off the WebView's context so
// it reflects the setting at the time of each page load (the activity restarts on a
// system theme change, since uiMode isn't declared in android:configChanges).
private fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

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
        micDiagnostics = MicDiagnostics(this, ::setKeepScreenOn)
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
        }
        setContent {
            AppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        SiteWebView(
                            onAudioPermissionRequest = ::handleWebAudioPermission,
                            micBridge = micBridge,
                        )
                        CaptureModeSelector(
                            modifier = Modifier.align(Alignment.BottomStart),
                            initialOverride = captureModePreference.override,
                            onOverrideChange = { captureModePreference.override = it },
                        )
                    }
                }
            }
        }
    }

    // A screen that sleeps mid-lecture kills the recording twice over, and silently: the
    // site auto-mutes on visibilitychange, and Android silences the mic for a backgrounded
    // app that has no microphone-type foreground service (see CLAUDE.md). Held only while
    // capture is actually live, so ordinary browsing still lets the screen time out.
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

    // Deliberately onDestroy and not onPause: the routing has to survive the screen going
    // off while a lecture is being recorded.
    override fun onDestroy() {
        micRouter.detach()
        micDiagnostics.detach()
        super.onDestroy()
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
    onAudioPermissionRequest: (PermissionRequest) -> Unit,
    micBridge: MicBridge,
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
