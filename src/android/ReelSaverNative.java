package com.artem.reelsaver;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReelSaverNative extends CordovaPlugin {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final int MAX_HTML_BYTES = 20 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 18000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int WEBVIEW_TIMEOUT_MS = 32000;
    private static final String IG_WEB_APP_ID = "936619743392459";
    private static final String IG_ASBD_ID = "359341";
    private static final String IG_GRAPHQL_DOC_ID = "27130156389949648";
    private static final String SHORTCODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final Pattern META_TAG = Pattern.compile("<meta\\s+[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([A-Za-z_:.-]+)\\s*=\\s*([\\\"'])(.*?)\\2", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern VIDEO_TAG = Pattern.compile("<(?:video|source)\\s+[^>]*(?:src|content)\\s*=\\s*([\\\"'])(https?:.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern VIDEO_URL_JSON = Pattern.compile("[\\\"'](?:video_url|videoUrl|playback_url|playbackUrl)[\\\"']\\s*:\\s*[\\\"'](https?:[^\\\"']+)[\\\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_URL_JSON = Pattern.compile("[\\\"'](?:contentUrl|content_url)[\\\"']\\s*:\\s*[\\\"'](https?:[^\\\"']+)[\\\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_MP4 = Pattern.compile("(https?:[^\\\"'<>\\s]+?\\.mp4(?:\\?[^\\\"'<>\\s]*)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORTCODE = Pattern.compile("/(?:reel|reels|p)/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTAGRAM_URL_IN_TEXT = Pattern.compile("https?://(?:[A-Za-z0-9-]+\\.)?instagram\\.com/[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern LSD_TOKEN = Pattern.compile("\\[\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SJS_SCRIPT = Pattern.compile("<script\\b[^>]*\\bdata-sjs[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private volatile String pendingSharedUrl = "";
    private volatile String browserUserAgent = USER_AGENT;
    private volatile String lastResolvedPageUrl = "https://www.instagram.com/";

    @Override
    protected void pluginInitialize() {
        captureSharedIntent(cordova.getActivity().getIntent());
    }

    @Override
    public void onNewIntent(Intent intent) {
        captureSharedIntent(intent);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if ("resolve".equals(action)) {
            final String pageUrl = args.optString(0, "");
            cordova.getThreadPool().execute(() -> resolveReel(pageUrl, callbackContext));
            return true;
        }
        if ("download".equals(action)) {
            final String mediaUrl = args.optString(0, "");
            final String filename = args.optString(1, "");
            cordova.getThreadPool().execute(() -> downloadVideo(mediaUrl, filename, callbackContext));
            return true;
        }
        if ("getSharedUrl".equals(action)) {
            JSONObject result = new JSONObject();
            try { result.put("url", consumeSharedUrl()); } catch (JSONException ignored) {}
            callbackContext.success(result);
            return true;
        }
        if ("getClipboard".equals(action)) {
            JSONObject result = new JSONObject();
            try { result.put("text", readClipboardText()); } catch (JSONException ignored) {}
            callbackContext.success(result);
            return true;
        }
        if ("openDownloads".equals(action)) {
            try {
                Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
                cordova.getActivity().startActivity(intent);
                callbackContext.success();
            } catch (Exception e) {
                callbackContext.error("Не удалось открыть системную папку загрузок: " + safeMessage(e));
            }
            return true;
        }
        return false;
    }

    private void captureSharedIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (extra == null) return;
        Matcher matcher = INSTAGRAM_URL_IN_TEXT.matcher(extra.toString());
        if (matcher.find()) pendingSharedUrl = trimPunctuation(matcher.group());
    }

    private synchronized String consumeSharedUrl() {
        String value = pendingSharedUrl;
        pendingSharedUrl = "";
        return value == null ? "" : value;
    }

    private String readClipboardText() {
        try {
            ClipboardManager clipboard = (ClipboardManager) cordova.getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null ||
                    clipboard.getPrimaryClip().getItemCount() == 0) return "";
            CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(cordova.getActivity());
            return text == null ? "" : text.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void resolveReel(String rawUrl, CallbackContext callback) {
        URL source;
        try {
            source = normalizeInstagramUrl(rawUrl);
        } catch (Exception e) {
            callback.error(safeMessage(e));
            return;
        }

        String shortcode = extractShortcode(source.toString());
        ReelInfo info = new ReelInfo();
        String directError = "";

        // Fast path: Instagram's own media endpoint. No third-party service is used.
        if (!shortcode.isEmpty()) {
            try {
                info.mergeMissing(resolveViaMediaInfo(shortcode, source.toString()));
            } catch (Exception e) {
                directError = safeMessage(e);
            }
            if (!info.mediaUrl.isEmpty()) {
                sendResolved(callback, source, shortcode, info, "instagram-media-info");
                return;
            }

            // Logged-out web flow used by Instagram itself: establish an anonymous
            // session, ask whether the media can be shown, then request its public data.
            try {
                info.mergeMissing(resolveViaLoggedOutGraphql(shortcode, source.toString()));
            } catch (Exception e) {
                directError = safeMessage(e);
            }
            if (!info.mediaUrl.isEmpty()) {
                sendResolved(callback, source, shortcode, info, "instagram-graphql");
                return;
            }
        }

        try {
            PageData page = fetchPage(source);
            if (shortcode.isEmpty()) shortcode = extractShortcode(page.finalUrl);
            info.mergeMissing(parseReelInfo(page.body, page.finalUrl, shortcode));

            // A share URL can hide the shortcode until the redirect. Once known,
            // retry the API paths because they are more reliable than HTML parsing.
            if (info.mediaUrl.isEmpty() && !shortcode.isEmpty()) {
                try { info.mergeMissing(resolveViaMediaInfo(shortcode, page.finalUrl)); } catch (Exception ignored) {}
                if (info.mediaUrl.isEmpty()) {
                    try { info.mergeMissing(resolveViaLoggedOutGraphql(shortcode, page.finalUrl)); } catch (Exception ignored) {}
                }
            }

            if (info.mediaUrl.isEmpty() && !shortcode.isEmpty()) {
                try {
                    URL embed = new URL("https://www.instagram.com/reel/" + shortcode + "/embed/");
                    PageData embedPage = fetchPage(embed);
                    info.mergeMissing(parseReelInfo(embedPage.body, embedPage.finalUrl, shortcode));
                } catch (Exception ignored) {}
            }

            if (info.mediaUrl.isEmpty() && !shortcode.isEmpty()) {
                try {
                    URL embedCaptioned = new URL("https://www.instagram.com/reel/" + shortcode + "/embed/captioned/");
                    PageData embedPage = fetchPage(embedCaptioned);
                    info.mergeMissing(parseReelInfo(embedPage.body, embedPage.finalUrl, shortcode));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            directError = safeMessage(e);
        }

        if (!info.mediaUrl.isEmpty()) {
            try {
                validateDownloadUrl(new URL(info.mediaUrl));
                sendResolved(callback, source, shortcode, info, "html");
                return;
            } catch (Exception e) {
                directError = safeMessage(e);
            }
        }

        resolveWithWebView(source, shortcode, info, directError, callback);
    }

    private ReelInfo resolveViaMediaInfo(String shortcode, String referer) throws Exception {
        String mediaId = shortcodeToMediaId(shortcode);
        InstagramSession session = new InstagramSession();
        Map<String, String> headers = instagramApiHeaders(referer);
        Exception last = null;
        String[] endpoints = {
                "https://www.instagram.com/api/v1/media/" + mediaId + "/info/",
                "https://i.instagram.com/api/v1/media/" + mediaId + "/info/"
        };
        for (String endpoint : endpoints) {
            try {
                JSONObject root = session.getJson(new URL(endpoint), headers);
                JSONArray items = root.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    ReelInfo info = reelInfoFromProduct(items.optJSONObject(0), shortcode);
                    if (!info.mediaUrl.isEmpty()) return info;
                }
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        return new ReelInfo();
    }

    private ReelInfo resolveViaLoggedOutGraphql(String shortcode, String referer) throws Exception {
        String mediaId = shortcodeToMediaId(shortcode);
        InstagramSession session = new InstagramSession();
        PageData home = session.getPage(new URL("https://www.instagram.com/"), baseWebHeaders("https://www.instagram.com/"));
        String lsd = extractLsdToken(home.body);
        if (lsd.isEmpty()) throw new IOException("Instagram session token was not found");

        Map<String, String> apiHeaders = instagramApiHeaders(referer);
        JSONObject ruling = session.getJson(new URL(
                "https://www.instagram.com/api/v1/web/get_ruling_for_content/?content_type=MEDIA&target_id=" + mediaId), apiHeaders);
        String status = ruling.optString("status", "");
        if (!status.isEmpty() && !"ok".equalsIgnoreCase(status)) {
            throw new IOException("Instagram did not grant anonymous access to this Reel");
        }

        String csrf = session.cookies.get("csrftoken");
        if (csrf == null || csrf.isEmpty()) throw new IOException("Instagram CSRF token was not set");

        Map<String, String> headers = instagramApiHeaders(referer);
        headers.put("X-FB-Friendly-Name", "PolarisLoggedOutDesktopWWWPostRootContentQuery");
        headers.put("X-CSRFToken", csrf);
        headers.put("X-FB-LSD", lsd);
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Content-Type", "application/x-www-form-urlencoded");

        String variables = "{\"media_id\":\"" + mediaId + "\"}";
        String form = formEncode(new String[][]{
                {"lsd", lsd},
                {"fb_api_caller_class", "RelayModern"},
                {"fb_api_req_friendly_name", "PolarisLoggedOutDesktopWWWPostRootContentQuery"},
                {"server_timestamps", "true"},
                {"variables", variables},
                {"doc_id", IG_GRAPHQL_DOC_ID}
        });
        JSONObject response = session.postJson(new URL("https://www.instagram.com/api/graphql"), headers, form);
        JSONObject data = response.optJSONObject("data");
        JSONObject media = data == null ? null : data.optJSONObject("xig_polaris_media");
        JSONObject product = media == null ? null : media.optJSONObject("if_not_gated_logged_out");
        if (product == null) throw new IOException("Instagram returned no public media data");
        ReelInfo info = reelInfoFromProduct(product, shortcode);
        if (info.mediaUrl.isEmpty()) throw new IOException("Instagram returned no video URL");
        return info;
    }

    private Map<String, String> instagramApiHeaders(String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-IG-App-ID", IG_WEB_APP_ID);
        headers.put("X-ASBD-ID", IG_ASBD_ID);
        headers.put("X-IG-WWW-Claim", "0");
        headers.put("Origin", "https://www.instagram.com");
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Referer", referer == null || referer.isEmpty() ? "https://www.instagram.com/" : referer);
        return headers;
    }

    private Map<String, String> baseWebHeaders(String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.6");
        headers.put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Referer", referer == null || referer.isEmpty() ? "https://www.instagram.com/" : referer);
        return headers;
    }

    private String shortcodeToMediaId(String shortcode) throws IOException {
        if (shortcode == null || shortcode.isEmpty()) throw new IOException("Reel code is empty");
        String clean = shortcode.length() > 28 ? shortcode.substring(0, shortcode.length() - 28) : shortcode;
        BigInteger value = BigInteger.ZERO;
        BigInteger radix = BigInteger.valueOf(64);
        for (int i = 0; i < clean.length(); i++) {
            int digit = SHORTCODE_ALPHABET.indexOf(clean.charAt(i));
            if (digit < 0) throw new IOException("Invalid Reel code");
            value = value.multiply(radix).add(BigInteger.valueOf(digit));
        }
        return value.toString();
    }

    private String extractLsdToken(String html) {
        if (html == null || html.isEmpty()) return "";
        Matcher matcher = LSD_TOKEN.matcher(html);
        if (matcher.find()) return decodeEscaped(matcher.group(1));
        // Fallback for slightly different serialized bootstrap markup.
        Matcher loose = Pattern.compile("\\\"LSD\\\".{0,120}?\\\"token\\\":\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        return loose.find() ? decodeEscaped(loose.group(1)) : "";
    }

    private ReelInfo reelInfoFromProduct(JSONObject product, String shortcode) {
        ReelInfo info = new ReelInfo();
        if (product == null) return info;

        JSONObject chosen = product;
        if (!hasVideo(chosen)) {
            JSONArray carousel = product.optJSONArray("carousel_media");
            if (carousel != null) {
                for (int i = 0; i < carousel.length(); i++) {
                    JSONObject candidate = carousel.optJSONObject(i);
                    if (hasVideo(candidate)) { chosen = candidate; break; }
                }
            }
        }

        JSONArray versions = chosen.optJSONArray("video_versions");
        long bestScore = -1;
        if (versions != null) {
            for (int i = 0; i < versions.length(); i++) {
                JSONObject v = versions.optJSONObject(i);
                if (v == null) continue;
                String url = normalizeExtractedUrl(v.optString("url", ""));
                if (url.isEmpty()) continue;
                long width = Math.max(1, v.optLong("width", 1));
                long height = Math.max(1, v.optLong("height", 1));
                long score = width * height;
                if (score > bestScore) {
                    bestScore = score;
                    info.mediaUrl = url;
                }
            }
        }
        if (info.mediaUrl.isEmpty()) {
            info.mediaUrl = normalizeExtractedUrl(chosen.optString("video_url", product.optString("video_url", "")));
        }

        JSONObject images = chosen.optJSONObject("image_versions2");
        JSONArray candidates = images == null ? null : images.optJSONArray("candidates");
        long bestImage = -1;
        if (candidates != null) {
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject c = candidates.optJSONObject(i);
                if (c == null) continue;
                String url = normalizeExtractedUrl(c.optString("url", ""));
                long score = Math.max(1, c.optLong("width", 1)) * Math.max(1, c.optLong("height", 1));
                if (!url.isEmpty() && score > bestImage) {
                    bestImage = score;
                    info.posterUrl = url;
                }
            }
        }
        if (info.posterUrl.isEmpty()) {
            info.posterUrl = normalizeExtractedUrl(chosen.optString("display_uri", product.optString("display_uri", "")));
        }

        JSONObject caption = product.optJSONObject("caption");
        String captionText = caption == null ? "" : caption.optString("text", "");
        JSONObject user = product.optJSONObject("user");
        String username = user == null ? "" : user.optString("username", "");
        if (!username.isEmpty()) info.title = "@" + username;
        else if (!captionText.isEmpty()) info.title = compactTitle(captionText);
        else info.title = "Instagram Reel";
        info.finalPageUrl = shortcode == null || shortcode.isEmpty() ? "" : "https://www.instagram.com/reel/" + shortcode + "/";
        return info;
    }

    private boolean hasVideo(JSONObject product) {
        if (product == null) return false;
        JSONArray versions = product.optJSONArray("video_versions");
        return (versions != null && versions.length() > 0) || !product.optString("video_url", "").isEmpty();
    }

    private String formEncode(String[][] pairs) throws Exception {
        StringBuilder out = new StringBuilder();
        for (String[] pair : pairs) {
            if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(pair[0], "UTF-8"));
            out.append('=');
            out.append(URLEncoder.encode(pair[1], "UTF-8"));
        }
        return out.toString();
    }

    private URL normalizeInstagramUrl(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) throw new SecurityException("Пустая ссылка.");
        Matcher matcher = INSTAGRAM_URL_IN_TEXT.matcher(raw.trim());
        String cleaned = matcher.find() ? trimPunctuation(matcher.group()) : trimPunctuation(raw.trim());
        URL url = new URL(cleaned);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new SecurityException("Нужна HTTPS-ссылка Instagram.");
        }
        String host = lower(url.getHost());
        if (!(host.equals("instagram.com") || host.endsWith(".instagram.com"))) {
            throw new SecurityException("Это не ссылка Instagram.");
        }
        // Do not require /reel/... here: current Instagram share links may use
        // /share/reel/... and redirect to the canonical Reel URL.
        return url;
    }

    private void validatePageRedirect(URL url) {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new SecurityException("Страница перенаправила запрос на небезопасный адрес.");
        }
    }

    private void validateDownloadUrl(URL url) {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new SecurityException("Видео должно загружаться по HTTPS.");
        }
        if (url.getHost() == null || url.getHost().trim().isEmpty()) {
            throw new SecurityException("Некорректный адрес видео.");
        }
        // No fixed CDN allow-list: Instagram changes Meta CDN hostnames regularly.
    }

    private PageData fetchPage(URL url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.6");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " от Instagram");
            }
            validatePageRedirect(connection.getURL());
            String finalUrl = connection.getURL().toString();
            String body = readTextLimited(connection.getInputStream(), MAX_HTML_BYTES);
            return new PageData(finalUrl, body);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(URL url, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Referer", "https://www.instagram.com/");
        connection.setRequestProperty("Cache-Control", "no-cache");
        return connection;
    }

    private ReelInfo parseReelInfo(String html, String finalPageUrl, String shortcode) {
        ReelInfo info = new ReelInfo();
        info.finalPageUrl = finalPageUrl == null ? "" : finalPageUrl;
        String source = html == null ? "" : html;

        // Instagram commonly embeds the actual media object inside <script data-sjs>.
        // Parse these JSON trees first because current pages often omit og:video.
        info.mergeMissing(parseDataSjsMedia(source, shortcode));

        Matcher tagMatcher = META_TAG.matcher(source);
        while (tagMatcher.find()) {
            String tag = tagMatcher.group();
            String property = "";
            String content = "";
            Matcher attrMatcher = ATTR.matcher(tag);
            while (attrMatcher.find()) {
                String key = lower(attrMatcher.group(1));
                String value = attrMatcher.group(3);
                if ("property".equals(key) || "name".equals(key) || "itemprop".equals(key)) property = lower(value);
                if ("content".equals(key)) content = decodeEscaped(value);
            }
            if (content.isEmpty()) continue;
            if (("og:video".equals(property) || "og:video:secure_url".equals(property) ||
                    "twitter:player:stream".equals(property) || "contenturl".equals(property)) && info.mediaUrl.isEmpty()) {
                info.mediaUrl = content;
            } else if (("og:image".equals(property) || "twitter:image".equals(property)) && info.posterUrl.isEmpty()) {
                info.posterUrl = content;
            } else if (("og:title".equals(property) || "twitter:title".equals(property)) && info.title.isEmpty()) {
                info.title = compactTitle(content);
            }
        }

        if (info.mediaUrl.isEmpty()) {
            Matcher videoTag = VIDEO_TAG.matcher(source);
            if (videoTag.find()) info.mediaUrl = decodeEscaped(videoTag.group(2));
        }
        if (info.mediaUrl.isEmpty()) info.mediaUrl = firstDecodedMatch(VIDEO_URL_JSON, source);
        if (info.mediaUrl.isEmpty()) info.mediaUrl = firstDecodedMatch(CONTENT_URL_JSON, source);
        if (info.mediaUrl.isEmpty()) info.mediaUrl = firstDecodedMatch(RAW_MP4, source);

        if (info.mediaUrl.isEmpty()) {
            String normalized = decodeEscaped(source);
            info.mediaUrl = firstDecodedMatch(VIDEO_URL_JSON, normalized);
            if (info.mediaUrl.isEmpty()) info.mediaUrl = firstDecodedMatch(CONTENT_URL_JSON, normalized);
            if (info.mediaUrl.isEmpty()) info.mediaUrl = firstDecodedMatch(RAW_MP4, normalized);
        }

        info.mediaUrl = normalizeExtractedUrl(info.mediaUrl);
        info.posterUrl = normalizeExtractedUrl(info.posterUrl);
        if (info.title.isEmpty() && !shortcode.isEmpty()) info.title = "Instagram Reel " + shortcode;
        return info;
    }

    private ReelInfo parseDataSjsMedia(String html, String shortcode) {
        ReelInfo result = new ReelInfo();
        if (html == null || html.isEmpty()) return result;
        Matcher matcher = SJS_SCRIPT.matcher(html);
        int parsed = 0;
        while (matcher.find() && parsed < 80 && result.mediaUrl.isEmpty()) {
            parsed++;
            String payload = matcher.group(1);
            if (payload == null || payload.trim().isEmpty()) continue;
            try {
                Object root = new JSONTokener(payload).nextValue();
                findMediaInJson(root, shortcode, result, 0);
            } catch (Exception ignored) {
                // Some script bodies contain HTML escaped JSON. Try once decoded.
                try {
                    Object root = new JSONTokener(decodeEscaped(payload)).nextValue();
                    findMediaInJson(root, shortcode, result, 0);
                } catch (Exception ignoredAgain) {}
            }
        }
        return result;
    }

    private void findMediaInJson(Object node, String shortcode, ReelInfo result, int depth) {
        if (node == null || node == JSONObject.NULL || depth > 90 || !result.mediaUrl.isEmpty()) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (hasVideo(object)) {
                ReelInfo candidate = reelInfoFromProduct(object, shortcode);
                result.mergeMissing(candidate);
                if (!result.mediaUrl.isEmpty()) return;
            }
            String direct = normalizeExtractedUrl(object.optString("video_url", ""));
            if (!direct.isEmpty()) {
                result.mediaUrl = direct;
                return;
            }
            JSONArray versions = object.optJSONArray("video_versions");
            if (versions != null) {
                long bestScore = -1;
                String best = "";
                for (int i = 0; i < versions.length(); i++) {
                    JSONObject version = versions.optJSONObject(i);
                    if (version == null) continue;
                    String url = normalizeExtractedUrl(version.optString("url", ""));
                    long score = Math.max(1, version.optLong("width", 1)) * Math.max(1, version.optLong("height", 1));
                    if (!url.isEmpty() && score > bestScore) {
                        bestScore = score;
                        best = url;
                    }
                }
                if (!best.isEmpty()) {
                    result.mediaUrl = best;
                    return;
                }
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext() && result.mediaUrl.isEmpty()) {
                String key = keys.next();
                Object child = object.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    findMediaInJson(child, shortcode, result, depth + 1);
                }
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            int limit = Math.min(array.length(), 1500);
            for (int i = 0; i < limit && result.mediaUrl.isEmpty(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    findMediaInJson(child, shortcode, result, depth + 1);
                }
            }
        }
    }

    private String firstDecodedMatch(Pattern pattern, String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return "";
        return normalizeExtractedUrl(decodeEscaped(matcher.group(1)));
    }

    private String normalizeExtractedUrl(String value) {
        if (value == null) return "";
        String s = decodeEscaped(value).trim();
        s = s.replace("\\u0026", "&").replace("\\u0025", "%").replace("\\/", "/");
        while (s.endsWith("\\")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void resolveWithWebView(URL source, String initialShortcode, ReelInfo seed,
                                    String directError, CallbackContext callback) {
        cordova.getActivity().runOnUiThread(() -> {
            final WebView webView = new WebView(cordova.getActivity());
            final FrameLayout webViewHost = new FrameLayout(cordova.getActivity());
            // Keep a real phone-sized WebView laid out behind the GDevelop view. Instagram
            // lazy-loads media based on viewport size, so the old 2x2 resolver never
            // requested the video on many public Reels.
            FrameLayout.LayoutParams hostParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.TOP | Gravity.START);
            webViewHost.setLayoutParams(hostParams);
            webViewHost.setAlpha(0.01f);
            webViewHost.setClickable(false);
            webViewHost.setFocusable(false);
            webViewHost.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            cordova.getActivity().addContentView(webViewHost, hostParams);
            try {
                if (ReelSaverNative.this.webView != null && ReelSaverNative.this.webView.getView() != null) {
                    ReelSaverNative.this.webView.getView().bringToFront();
                }
            } catch (Exception ignored) {}
            final Handler handler = new Handler(Looper.getMainLooper());
            final AtomicBoolean finished = new AtomicBoolean(false);
            final AtomicReference<String> interceptedVideo = new AtomicReference<>("");
            final AtomicBoolean browserApiStarted = new AtomicBoolean(false);
            final AtomicBoolean embedFallbackLoaded = new AtomicBoolean(false);
            final long startedAt = System.currentTimeMillis();

            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setDatabaseEnabled(true);
            webView.getSettings().setLoadsImagesAutomatically(true);
            webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
            // Keep the real Android WebView UA. A forged desktop/mobile Chrome UA with
            // a different TLS/browser fingerprint is much more likely to be gated by Instagram.
            String realWebViewUa = webView.getSettings().getUserAgentString();
            if (realWebViewUa != null && !realWebViewUa.trim().isEmpty()) browserUserAgent = realWebViewUa;
            webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
            webView.getSettings().setUseWideViewPort(true);
            webView.getSettings().setLoadWithOverviewMode(false);
            webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
            webView.setInitialScale(100);
            try {
                CookieManager.getInstance().setAcceptCookie(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
            } catch (Exception ignored) {}

            final Runnable cleanup = () -> {
                try { webView.stopLoading(); } catch (Exception ignored) {}
                try { webView.loadUrl("about:blank"); } catch (Exception ignored) {}
                try { webView.destroy(); } catch (Exception ignored) {}
                try {
                    ViewGroup parent = (ViewGroup) webViewHost.getParent();
                    if (parent != null) parent.removeView(webViewHost);
                } catch (Exception ignored) {}
            };

            final Runnable timeout = () -> {
                if (!finished.compareAndSet(false, true)) return;
                cleanup.run();
                callback.error("Не удалось получить видео. Попробуйте ещё раз чуть позже.");
            };
            handler.postDelayed(timeout, WEBVIEW_TIMEOUT_MS);

            class Poller implements Runnable {
                int attempts = 0;

                @Override
                public void run() {
                    if (finished.get()) return;
                    attempts++;

                    String currentShortcode = initialShortcode.isEmpty()
                            ? extractShortcode(webView.getUrl()) : initialShortcode;
                    if (!currentShortcode.isEmpty() && browserApiStarted.compareAndSet(false, true)) {
                        try {
                            String mediaId = shortcodeToMediaId(currentShortcode);
                            webView.evaluateJavascript(webViewApiFetchScript(currentShortcode, mediaId), ignored -> {});
                        } catch (Exception ignored) {}
                    }

                    String captured = interceptedVideo.get();
                    if (!captured.isEmpty()) {
                        ReelInfo result = new ReelInfo();
                        result.mergeMissing(seed);
                        result.mediaUrl = captured;
                        result.finalPageUrl = webView.getUrl() == null ? source.toString() : webView.getUrl();
                        String shortcode = initialShortcode.isEmpty() ? extractShortcode(result.finalPageUrl) : initialShortcode;
                        if (finishWebViewResolve(finished, handler, timeout, cleanup, callback, source, shortcode, result, "webview-network")) return;
                    }

                    webView.evaluateJavascript(webViewExtractorScript(), raw -> {
                        if (finished.get()) return;
                        try {
                            String decoded = decodeEvaluateJavascriptResult(raw);
                            if (!decoded.isEmpty()) {
                                JSONObject data = new JSONObject(decoded);
                                ReelInfo result = new ReelInfo();
                                result.mergeMissing(seed);
                                String media = normalizeExtractedUrl(data.optString("mediaUrl", ""));
                                String poster = normalizeExtractedUrl(data.optString("posterUrl", ""));
                                String title = compactTitle(data.optString("title", ""));
                                String finalUrl = data.optString("finalUrl", webView.getUrl());
                                if (!media.isEmpty()) result.mediaUrl = media;
                                if (!poster.isEmpty()) result.posterUrl = poster;
                                if (!title.isEmpty()) result.title = title;
                                if (finalUrl != null) result.finalPageUrl = finalUrl;
                                String shortcode = initialShortcode.isEmpty() ? extractShortcode(result.finalPageUrl) : initialShortcode;
                                if (!result.mediaUrl.isEmpty() &&
                                        finishWebViewResolve(finished, handler, timeout, cleanup, callback, source, shortcode, result, "webview-dom")) {
                                    return;
                                }
                            }
                        } catch (Exception ignored) {}

                        if (!finished.get() && System.currentTimeMillis() - startedAt < WEBVIEW_TIMEOUT_MS - 800) {
                            handler.postDelayed(this, attempts < 4 ? 700 : 1200);
                        }
                    });
                }
            }

            Poller poller = new Poller();
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    browserApiStarted.set(false);
                    try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
                    handler.post(poller);
                }

                @Override
                public void onLoadResource(WebView view, String url) {
                    super.onLoadResource(view, url);
                    if (looksLikeVideoUrl(url) && interceptedVideo.get().isEmpty()) {
                        interceptedVideo.compareAndSet("", url);
                        handler.post(poller);
                    }
                }

                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (request != null && request.getUrl() != null) {
                        String candidate = request.getUrl().toString();
                        if (looksLikeVideoUrl(candidate) && interceptedVideo.get().isEmpty()) {
                            interceptedVideo.compareAndSet("", candidate);
                            handler.post(poller);
                        }
                    }
                    return super.shouldInterceptRequest(view, request);
                }
            });

            Map<String, String> headers = new HashMap<>();
            headers.put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Referer", "https://www.instagram.com/");
            webView.loadUrl(source.toString(), headers);
            handler.postDelayed(poller, 1200);

            // If the normal Reel page is login-gated or does not start media playback,
            // switch the same browser session to Instagram's embed page. The embed
            // renderer is intentionally usable on third-party pages and often exposes
            // the public video when the normal logged-out page does not.
            handler.postDelayed(() -> {
                if (finished.get() || initialShortcode.isEmpty() || !embedFallbackLoaded.compareAndSet(false, true)) return;
                browserApiStarted.set(false);
                String embedUrl = "https://www.instagram.com/reel/" + initialShortcode + "/embed/";
                webView.loadUrl(embedUrl, headers);
            }, 8500);

            handler.postDelayed(() -> {
                if (finished.get() || initialShortcode.isEmpty()) return;
                String current = webView.getUrl() == null ? "" : webView.getUrl();
                if (current.contains("/embed/")) {
                    browserApiStarted.set(false);
                    webView.loadUrl("https://www.instagram.com/reel/" + initialShortcode + "/embed/captioned/", headers);
                }
            }, 15000);
        });
    }

    private boolean finishWebViewResolve(AtomicBoolean finished, Handler handler, Runnable timeout,
                                         Runnable cleanup, CallbackContext callback, URL source,
                                         String shortcode, ReelInfo result, String resolver) {
        try {
            validateDownloadUrl(new URL(result.mediaUrl));
        } catch (Exception e) {
            return false;
        }
        if (!finished.compareAndSet(false, true)) return true;
        handler.removeCallbacks(timeout);
        sendResolved(callback, source, shortcode, result, resolver);
        cleanup.run();
        return true;
    }

    private void sendResolved(CallbackContext callback, URL source, String shortcode,
                              ReelInfo info, String resolver) {
        try {
            if (info.finalPageUrl != null && !info.finalPageUrl.trim().isEmpty()) {
                lastResolvedPageUrl = info.finalPageUrl;
            } else if (source != null) {
                lastResolvedPageUrl = source.toString();
            }
            JSONObject json = new JSONObject();
            json.put("sourceUrl", source.toString());
            json.put("finalPageUrl", info.finalPageUrl);
            json.put("mediaUrl", info.mediaUrl);
            json.put("posterUrl", info.posterUrl);
            json.put("title", info.title.isEmpty() ? "Instagram Reel" : info.title);
            json.put("shortcode", shortcode == null ? "" : shortcode);
            json.put("resolver", resolver);
            callback.success(json);
        } catch (JSONException e) {
            callback.error("Не удалось сформировать данные Reel: " + safeMessage(e));
        }
    }

    private String webViewExtractorScript() {
        return "(function(){" +
                "function attr(s,a){try{var e=document.querySelector(s);return e?(e.getAttribute(a)||''):'';}catch(e){return '';}}" +
                "function norm(u){return typeof u==='string'&&/^https:/i.test(u)?u:'';}" +
                "function scan(v,d){if(!v||d>70)return '';if(Array.isArray(v)){for(var i=0;i<v.length;i++){var x=scan(v[i],d+1);if(x)return x;}return '';}if(typeof v!=='object')return '';" +
                "if(Array.isArray(v.video_versions)){var best='',score=-1;for(var j=0;j<v.video_versions.length;j++){var q=v.video_versions[j]||{},u=norm(q.url||'');var s=(q.width||1)*(q.height||1);if(u&&s>score){best=u;score=s;}}if(best)return best;}" +
                "var direct=norm(v.video_url||v.videoUrl||v.playback_url||v.playbackUrl||'');if(direct)return direct;" +
                "for(var k in v){if(!Object.prototype.hasOwnProperty.call(v,k))continue;var z=v[k];if(z&&typeof z==='object'){var r=scan(z,d+1);if(r)return r;}}return '';}" +
                "var media=attr('meta[property=\\\"og:video:secure_url\\\"]','content')||attr('meta[property=\\\"og:video\\\"]','content')||attr('meta[name=\\\"twitter:player:stream\\\"]','content');" +
                "if(!media){var scripts=document.querySelectorAll('script[data-sjs],script[type=\\\"application/json\\\"]');for(var si=0;si<scripts.length&&!media;si++){var txt=scripts[si].textContent||'';if(!txt||txt.length>12000000)continue;try{media=scan(JSON.parse(txt),0)||'';}catch(e){}}}" +
                "var vs=document.querySelectorAll('video');for(var vi=0;vi<vs.length;vi++){try{vs[vi].muted=true;vs[vi].preload='auto';var p=vs[vi].play();if(p&&p.catch)p.catch(function(){});}catch(e){}if(!media)media=vs[vi].currentSrc||vs[vi].src||'';}" +
                "if(!media){var ss=document.querySelectorAll('source');for(var sj=0;sj<ss.length;sj++){media=ss[sj].src||ss[sj].getAttribute('src')||'';if(media)break;}}" +
                "if(!media&&window.performance&&performance.getEntriesByType){var rs=performance.getEntriesByType('resource');for(var ri=rs.length-1;ri>=0;ri--){var u=rs[ri].name||'';if(/^https:/i.test(u)&&/\\.mp4(?:\\?|$)/i.test(u)){media=u;break;}}}" +
                "var poster=attr('meta[property=\\\"og:image\\\"]','content')||attr('meta[name=\\\"twitter:image\\\"]','content')||attr('video','poster');" +
                "var title=attr('meta[property=\\\"og:title\\\"]','content')||document.title||'';" +
                "return JSON.stringify({mediaUrl:media||'',posterUrl:poster||'',title:title||'',finalUrl:location.href||''});" +
                "})()";
    }

    private String webViewApiFetchScript(String shortcode, String mediaId) {
        String safeShortcode = shortcode == null ? "" : shortcode.replace("\\", "\\\\").replace("'", "\\'");
        String safeMediaId = mediaId == null ? "" : mediaId.replace("\\", "\\\\").replace("'", "\\'");
        return "(function(){" +
                "if(window.__reelsaverApiRunning)return;window.__reelsaverApiRunning=true;window.__reelsaverApiResult={state:'loading'};" +
                "var MID='" + safeMediaId + "',SC='" + safeShortcode + "',APP='" + IG_WEB_APP_ID + "',ASBD='" + IG_ASBD_ID + "',DOC='" + IG_GRAPHQL_DOC_ID + "';" +
                "function ck(n){var a=(document.cookie||'').split(';');for(var i=0;i<a.length;i++){var p=a[i].trim();if(p.indexOf(n+'=')===0)return decodeURIComponent(p.substring(n.length+1));}return '';}" +
                "function lsd(){try{var e=document.getElementById('__eqmc');if(e){var j=JSON.parse(e.textContent||'{}');if(j&&j.l)return j.l;}}catch(e){}" +
                "var h='';try{h=document.documentElement.innerHTML||'';}catch(e){}var m=h.match(/\\[\\\"LSD\\\",\\[\\],\\{\\\"token\\\":\\\"([^\\\"]+)/);return m?m[1]:'';}" +
                "function norm(u){return typeof u==='string'&&/^https:/i.test(u)?u:'';}" +
                "function scan(v,d){if(!v||d>80)return '';if(Array.isArray(v)){for(var i=0;i<v.length;i++){var r=scan(v[i],d+1);if(r)return r;}return '';}if(typeof v!=='object')return '';" +
                "if(Array.isArray(v.video_versions)){var b='',bs=-1;for(var j=0;j<v.video_versions.length;j++){var q=v.video_versions[j]||{},u=norm(q.url||''),s=(q.width||1)*(q.height||1);if(u&&s>bs){b=u;bs=s;}}if(b)return b;}" +
                "var x=norm(v.video_url||v.videoUrl||v.playback_url||v.playbackUrl||'');if(x)return x;for(var k in v){if(Object.prototype.hasOwnProperty.call(v,k)&&v[k]&&typeof v[k]==='object'){var z=scan(v[k],d+1);if(z)return z;}}return '';}" +
                "async function run(){try{" +
                "var base={'X-IG-App-ID':APP,'X-ASBD-ID':ASBD,'X-IG-WWW-Claim':'0','X-Requested-With':'XMLHttpRequest'};" +
                "try{await fetch('/api/v1/web/get_ruling_for_content/?content_type=MEDIA&target_id='+encodeURIComponent(MID),{credentials:'include',headers:base,cache:'no-store'});}catch(e){}" +
                "var L=lsd(),C=ck('csrftoken');if(!L)throw new Error('no_lsd');" +
                "var body=new URLSearchParams();body.set('lsd',L);body.set('fb_api_caller_class','RelayModern');body.set('fb_api_req_friendly_name','PolarisLoggedOutDesktopWWWPostRootContentQuery');body.set('server_timestamps','true');body.set('variables',JSON.stringify({media_id:MID}));body.set('doc_id',DOC);" +
                "var hd=Object.assign({},base,{'X-FB-Friendly-Name':'PolarisLoggedOutDesktopWWWPostRootContentQuery','X-FB-LSD':L,'Content-Type':'application/x-www-form-urlencoded'});if(C)hd['X-CSRFToken']=C;" +
                "var r=await fetch('/api/graphql',{method:'POST',credentials:'include',headers:hd,body:body.toString(),cache:'no-store'});var t=await r.text();var j={};try{j=JSON.parse(t);}catch(e){}var u=scan(j,0);" +
                "if(u){window.__reelsaverApiResult={state:'done',mediaUrl:u};return;}throw new Error('no_video');" +
                "}catch(e){window.__reelsaverApiResult={state:'error',mediaUrl:'',error:String(e&&e.message||e)};}finally{window.__reelsaverApiRunning=false;}}run();return 'started';" +
                "})()";
    }

    private String decodeEvaluateJavascriptResult(String raw) {
        if (raw == null || raw.equals("null") || raw.equals("undefined")) return "";
        try {
            JSONArray wrapper = new JSONArray("[" + raw + "]");
            return wrapper.optString(0, "");
        } catch (Exception e) {
            return "";
        }
    }

    private boolean looksLikeVideoUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.US);
        return lower.startsWith("https://") &&
                (lower.contains(".mp4?") || lower.endsWith(".mp4"));
    }

    private void downloadVideo(String rawMediaUrl, String requestedFilename, CallbackContext callback) {
        Uri insertedUri = null;
        HttpURLConnection connection = null;
        OutputStream output = null;
        InputStream input = null;
        try {
            URL mediaUrl = new URL(rawMediaUrl);
            validateDownloadUrl(mediaUrl);
            String filename = sanitizeFilename(requestedFilename);
            if (filename.isEmpty()) filename = defaultFilename();
            if (!filename.toLowerCase(Locale.US).endsWith(".mp4")) filename += ".mp4";

            connection = openMediaConnection(mediaUrl);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " при загрузке видео");
            validateDownloadUrl(connection.getURL());
            String contentType = lower(connection.getContentType());
            if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
                throw new IOException("Instagram вернул страницу вместо видео — ссылка на MP4 устарела или требует повторного получения");
            }
            long total = contentLength(connection);
            input = new BufferedInputStream(connection.getInputStream(), 64 * 1024);

            String savedLocation;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = cordova.getActivity().getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ReelSaver");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                insertedUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (insertedUri == null) throw new IOException("Android не дал создать файл в Downloads");
                OutputStream rawOutput = resolver.openOutputStream(insertedUri, "w");
                if (rawOutput == null) throw new IOException("Android MediaStore не выдал поток для записи файла");
                output = new BufferedOutputStream(rawOutput, 64 * 1024);
                savedLocation = Environment.DIRECTORY_DOWNLOADS + "/ReelSaver/" + filename;
            } else {
                File base = cordova.getActivity().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (base == null) throw new IOException("Внешнее хранилище недоступно");
                File dir = new File(base, "ReelSaver");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Не удалось создать папку ReelSaver");
                File outFile = uniqueFile(dir, filename);
                output = new BufferedOutputStream(new FileOutputStream(outFile), 64 * 1024);
                savedLocation = outFile.getAbsolutePath();
            }

            byte[] buffer = new byte[64 * 1024];
            long downloaded = 0;
            long lastReportAt = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                downloaded += read;
                if (downloaded - lastReportAt >= 512 * 1024) {
                    sendProgress(callback, downloaded, total);
                    lastReportAt = downloaded;
                }
            }
            output.flush();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && insertedUri != null) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cordova.getActivity().getContentResolver().update(insertedUri, ready, null, null);
            }

            JSONObject done = new JSONObject();
            done.put("state", "done");
            done.put("bytes", downloaded);
            done.put("path", savedLocation);
            done.put("message", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? "Видео сохранено в Загрузки/ReelSaver."
                    : "Видео сохранено в папку приложения: " + savedLocation);
            callback.success(done);
        } catch (SecurityException e) {
            cleanupPending(insertedUri);
            callback.error(e.getMessage());
        } catch (Exception e) {
            cleanupPending(insertedUri);
            callback.error("Ошибка скачивания: " + safeMessage(e));
        } finally {
            closeQuietly(input);
            closeQuietly(output);
            if (connection != null) connection.disconnect();
        }
    }

    private void sendProgress(CallbackContext callback, long bytes, long total) {
        try {
            JSONObject data = new JSONObject();
            data.put("state", "progress");
            data.put("bytes", bytes);
            data.put("total", total);
            data.put("percent", total > 0 ? (bytes * 100.0 / total) : -1);
            PluginResult result = new PluginResult(PluginResult.Status.OK, data);
            result.setKeepCallback(true);
            callback.sendPluginResult(result);
        } catch (JSONException ignored) {}
    }

    private void cleanupPending(Uri uri) {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try { cordova.getActivity().getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
    }

    private HttpURLConnection openMediaConnection(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", browserUserAgent == null || browserUserAgent.trim().isEmpty() ? USER_AGENT : browserUserAgent);
        connection.setRequestProperty("Accept", "video/mp4,video/*;q=0.9,application/octet-stream;q=0.8,*/*;q=0.5");
        connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Referer", lastResolvedPageUrl == null || lastResolvedPageUrl.trim().isEmpty()
                ? "https://www.instagram.com/" : lastResolvedPageUrl);
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            String cookie = CookieManager.getInstance().getCookie(url.toString());
            if (cookie != null && !cookie.trim().isEmpty()) connection.setRequestProperty("Cookie", cookie);
        } catch (Exception ignored) {}
        return connection;
    }

    private long contentLength(HttpURLConnection connection) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return connection.getContentLengthLong();
        return connection.getContentLength();
    }

    private File uniqueFile(File dir, String filename) {
        File candidate = new File(dir, filename);
        if (!candidate.exists()) return candidate;
        String base = filename;
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) {
            base = filename.substring(0, dot);
            ext = filename.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            candidate = new File(dir, base + "_" + i + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    private String sanitizeFilename(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        safe = safe.replaceAll("_+", "_");
        if (safe.length() > 120) safe = safe.substring(0, 120);
        return safe;
    }

    private String defaultFilename() {
        return "reel_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
    }

    private String readTextLimited(InputStream in, int maxBytes) throws IOException {
        try (InputStream input = new BufferedInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(512 * 1024, maxBytes))) {
            byte[] buffer = new byte[32 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Страница Instagram слишком большая");
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String decodeEscaped(String value) {
        if (value == null) return "";
        String s = value;
        s = s.replace("&amp;", "&").replace("&#38;", "&").replace("&quot;", "\"")
                .replace("&#34;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
        s = s.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=")
                .replace("\\u003D", "=").replace("\\u0025", "%");

        Pattern unicode = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
        Matcher matcher = unicode.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            char c = (char) Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(c)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String compactTitle(String value) {
        String s = decodeEscaped(value).replaceAll("\\s+", " ").trim();
        if (s.length() > 180) s = s.substring(0, 177) + "…";
        return s;
    }

    private String extractShortcode(String value) {
        Matcher matcher = SHORTCODE.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String trimPunctuation(String value) {
        if (value == null) return "";
        return value.replaceAll("[),.;!?]+$", "");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return (message == null || message.trim().isEmpty()) ? e.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception ignored) {}
    }

    private final class InstagramSession {
        final Map<String, String> cookies = new LinkedHashMap<>();

        PageData getPage(URL url, Map<String, String> headers) throws Exception {
            HttpURLConnection connection = request(url, "GET", headers, null);
            try {
                int code = connection.getResponseCode();
                updateCookies(connection);
                if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " from Instagram");
                String body = readTextLimited(connection.getInputStream(), MAX_HTML_BYTES);
                return new PageData(connection.getURL().toString(), body);
            } finally {
                connection.disconnect();
            }
        }

        JSONObject getJson(URL url, Map<String, String> headers) throws Exception {
            return jsonRequest(url, "GET", headers, null);
        }

        JSONObject postJson(URL url, Map<String, String> headers, String form) throws Exception {
            return jsonRequest(url, "POST", headers, form);
        }

        private JSONObject jsonRequest(URL url, String method, Map<String, String> headers, String body) throws Exception {
            HttpURLConnection connection = request(url, method, headers, body);
            try {
                int code = connection.getResponseCode();
                updateCookies(connection);
                InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
                String text = stream == null ? "" : readTextLimited(stream, MAX_HTML_BYTES);
                if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " from Instagram");
                return new JSONObject(text);
            } finally {
                connection.disconnect();
            }
        }

        private HttpURLConnection request(URL url, String method, Map<String, String> headers, String body) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod(method);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (!cookies.isEmpty()) connection.setRequestProperty("Cookie", cookieHeader());
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
            }
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            return connection;
        }

        private void updateCookies(HttpURLConnection connection) {
            Map<String, List<String>> fields = connection.getHeaderFields();
            if (fields == null) return;
            for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
                if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
                List<String> values = entry.getValue();
                if (values == null) continue;
                for (String header : values) {
                    if (header == null || header.isEmpty()) continue;
                    int semicolon = header.indexOf(';');
                    String first = semicolon >= 0 ? header.substring(0, semicolon) : header;
                    int equals = first.indexOf('=');
                    if (equals <= 0) continue;
                    String name = first.substring(0, equals).trim();
                    String value = first.substring(equals + 1).trim();
                    if (!name.isEmpty()) cookies.put(name, value);
                }
            }
        }

        private String cookieHeader() {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (out.length() > 0) out.append("; ");
                out.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return out.toString();
        }
    }

    private static final class PageData {
        final String finalUrl;
        final String body;
        PageData(String finalUrl, String body) {
            this.finalUrl = finalUrl;
            this.body = body;
        }
    }

    private static final class ReelInfo {
        String finalPageUrl = "";
        String mediaUrl = "";
        String posterUrl = "";
        String title = "";

        void mergeMissing(ReelInfo other) {
            if (other == null) return;
            if (finalPageUrl.isEmpty()) finalPageUrl = other.finalPageUrl;
            if (mediaUrl.isEmpty()) mediaUrl = other.mediaUrl;
            if (posterUrl.isEmpty()) posterUrl = other.posterUrl;
            if (title.isEmpty()) title = other.title;
        }
    }
}
