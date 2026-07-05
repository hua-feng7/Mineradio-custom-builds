package com.mineradio.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class LocalHttpServer {
    private static final String TAG = "MineradioHttp";
    private static final String ASSET_ROOT = "www";
    private static final String PREFS_NAME = "mineradio_android_state";
    private static final String PREF_NETEASE_COOKIE = "netease_cookie";
    private static final String PREF_QQ_COOKIE = "qq_cookie";
    private static final String PREF_CUSTOM_PLAYLISTS = "custom_playlists";
    private static final String PREF_CUSTOM_TRACKS = "custom_playlist_tracks";
    private static final String PREF_LIKED_STATE = "liked_song_state";
    private static final String PREF_CUSTOM_SEQ = "custom_playlist_seq";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36 MineradioAndroid";
    private static final String QQ_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 MineradioAndroid/1.0";
    private static final String QQ_QRC_DOWNLOAD_URL = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg";
    private static final int LOCAL_HTTP_PORT = 27841;
    private static final String NETEASE_WEAPI_KEY = "0CoJUm6Qyw8W8jud";
    private static final String NETEASE_WEAPI_IV = "0102030405060708";
    private static final String NETEASE_RSA_PUBLIC_EXPONENT = "010001";
    private static final String NETEASE_RSA_MODULUS =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
    private static final String NETEASE_BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int BEATMAP_CACHE_MAX_ENTRIES = 200;

    private final Context context;
    private final AssetManager assets;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, JSONArray> starterTrackCache = new HashMap<>();
    private final Map<String, Boolean> likedSongState = new HashMap<>();
    private final Map<String, JSONArray> customPlaylistTracks = new HashMap<>();
    private final List<JSONObject> customPlaylists = new ArrayList<>();
    private int customPlaylistSeq = 1;
    private String neteaseCookie = "";
    private String qqCookie = "";
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private int port;

    public LocalHttpServer(Context context) {
        this.context = context.getApplicationContext();
        this.assets = this.context.getAssets();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadPersistedState();
    }

    private void loadPersistedState() {
        neteaseCookie = prefs.getString(PREF_NETEASE_COOKIE, "");
        qqCookie = prefs.getString(PREF_QQ_COOKIE, "");
        customPlaylistSeq = Math.max(1, prefs.getInt(PREF_CUSTOM_SEQ, 1));
        synchronized (customPlaylists) {
            customPlaylists.clear();
            customPlaylistTracks.clear();
            try {
                JSONArray playlists = new JSONArray(prefs.getString(PREF_CUSTOM_PLAYLISTS, "[]"));
                for (int i = 0; i < playlists.length(); i++) {
                    JSONObject playlist = playlists.optJSONObject(i);
                    if (playlist != null) customPlaylists.add(new JSONObject(playlist.toString()));
                }
            } catch (Exception e) {
                Log.w(TAG, "custom playlists load failed", e);
            }
            try {
                JSONObject tracks = new JSONObject(prefs.getString(PREF_CUSTOM_TRACKS, "{}"));
                Iterator<String> keys = tracks.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONArray value = tracks.optJSONArray(key);
                    if (value != null) customPlaylistTracks.put(key, new JSONArray(value.toString()));
                }
            } catch (Exception e) {
                Log.w(TAG, "custom tracks load failed", e);
            }
        }
        synchronized (likedSongState) {
            likedSongState.clear();
            try {
                JSONObject liked = new JSONObject(prefs.getString(PREF_LIKED_STATE, "{}"));
                Iterator<String> keys = liked.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    likedSongState.put(key, liked.optBoolean(key, false));
                }
            } catch (Exception e) {
                Log.w(TAG, "liked state load failed", e);
            }
        }
    }

    private void persistState() {
        try {
            JSONArray playlists = new JSONArray();
            JSONObject tracks = new JSONObject();
            JSONObject liked = new JSONObject();
            synchronized (customPlaylists) {
                for (int i = 0; i < customPlaylists.size(); i++) {
                    JSONObject playlist = customPlaylists.get(i);
                    if (playlist != null) playlists.put(new JSONObject(playlist.toString()));
                }
                for (Map.Entry<String, JSONArray> entry : customPlaylistTracks.entrySet()) {
                    tracks.put(entry.getKey(), entry.getValue() == null ? new JSONArray() : new JSONArray(entry.getValue().toString()));
                }
            }
            synchronized (likedSongState) {
                for (Map.Entry<String, Boolean> entry : likedSongState.entrySet()) {
                    liked.put(entry.getKey(), Boolean.TRUE.equals(entry.getValue()));
                }
            }
            prefs.edit()
                    .putString(PREF_NETEASE_COOKIE, neteaseCookie == null ? "" : neteaseCookie)
                    .putString(PREF_QQ_COOKIE, qqCookie == null ? "" : qqCookie)
                    .putString(PREF_CUSTOM_PLAYLISTS, playlists.toString())
                    .putString(PREF_CUSTOM_TRACKS, tracks.toString())
                    .putString(PREF_LIKED_STATE, liked.toString())
                    .putInt(PREF_CUSTOM_SEQ, customPlaylistSeq)
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "persist state failed", e);
        }
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_HTTP_PORT), 24);
        port = serverSocket.getLocalPort();
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() {
                acceptLoop();
            }
        }, "MineradioLocalHttp");
        acceptThread.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port + "/";
    }

    public void stop() {
        running = false;
        closeQuietly(serverSocket);
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                executor.execute(new Runnable() {
                    @Override public void run() {
                        handleSocket(socket);
                    }
                });
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private void handleSocket(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.trim().isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendText(output, 400, "text/plain; charset=utf-8", "Bad Request");
                return;
            }
            String method = parts[0].trim().toUpperCase(Locale.US);
            String target = parts[1].trim();
            Map<String, String> headers = readHeaders(input);
            byte[] body = readBody(input, headers);
            if ("OPTIONS".equals(method)) {
                sendBytes(output, 204, "text/plain", new byte[0], corsHeaders());
                return;
            }
            RequestUrl url = parseRequestTarget(target);
            if (url.path.startsWith("/api/")) {
                handleApi(output, method, url, headers, body);
            } else {
                serveAsset(output, method, url.path);
            }
        } catch (Exception e) {
            try {
                sendJson(socketSafeOutput(socket), 500, jsonError(e.getMessage()));
            } catch (Exception ignored) {
            }
            Log.w(TAG, "request failed", e);
        } finally {
            closeQuietly(socket);
        }
    }

    private OutputStream socketSafeOutput(Socket socket) throws IOException {
        return socket.getOutputStream();
    }

    private void handleApi(OutputStream output, String method, RequestUrl url, Map<String, String> headers, byte[] body) throws Exception {
        String path = url.path;
        if ("/api/app/version".equals(path)) {
            JSONObject out = new JSONObject();
            out.put("name", "Mineradio");
            out.put("version", "1.1.1-android");
            out.put("platform", "android");
            sendJson(output, 200, out);
            return;
        }
        if ("/api/beatmap/cache/status".equals(path)) {
            sendJson(output, 200, beatmapCacheStatus());
            return;
        }
        if ("/api/beatmap/cache".equals(path)) {
            if ("POST".equals(method)) {
                sendJson(output, 200, beatmapCachePut(body));
                return;
            }
            if ("GET".equals(method)) {
                sendJson(output, 200, beatmapCacheGet(url));
                return;
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("enabled", true);
            out.put("mode", "disk");
            out.put("found", false);
            out.put("hit", false);
            out.put("skipped", true);
            sendJson(output, 200, out);
            return;
        }
        if ("/api/discover/home".equals(path)) {
            sendJson(output, 200, starterDiscoverHome());
            return;
        }
        if ("/api/weather/ip-location".equals(path)) {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("location", defaultLocation(url.param("city", "Shanghai")));
            sendJson(output, 200, out);
            return;
        }
        if ("/api/weather/radio".equals(path)) {
            sendJson(output, 200, weatherRadio(url));
            return;
        }
        if ("/api/search".equals(path)) {
            String keywords = url.param("keywords", "");
            int limit = clampInt(url.param("limit", "20"), 4, 60, 20);
            int page = clampInt(url.param("page", url.param("pageNo", "1")), 1, 1000, 1);
            sendJson(output, 200, searchNeteaseResult(keywords, limit, page));
            return;
        }
        if ("/api/song/url".equals(path)) {
            sendJson(output, 200, neteaseSongUrl(url.param("id", ""), url.param("quality", "standard")));
            return;
        }
        if ("/api/lyric".equals(path)) {
            sendJson(output, 200, neteaseLyric(url.param("id", "")));
            return;
        }
        if ("/api/cover".equals(path)) {
            proxy(output, url.param("url", ""), headers, false);
            return;
        }
        if ("/api/audio".equals(path)) {
            proxy(output, url.param("url", ""), headers, true);
            return;
        }
        if ("/api/qq/search".equals(path)) {
            String keywords = url.param("keywords", "");
            int limit = clampInt(url.param("limit", "24"), 4, 60, 24);
            int page = clampInt(url.param("page", url.param("pageNo", "1")), 1, 1000, 1);
            sendJson(output, 200, searchQQ(keywords, limit, page));
            return;
        }
        if ("/api/qq/song/url".equals(path)) {
            String mid = url.param("mid", url.param("id", ""));
            String mediaMid = url.param("mediaMid", url.param("media_mid", mid));
            String quality = url.param("quality", "standard");
            sendJson(output, 200, qqSongUrl(mid, mediaMid, quality));
            return;
        }
        if ("/api/qq/lyric".equals(path)) {
            String mid = url.param("mid", url.param("songmid", ""));
            String id = url.param("id", url.param("qqId", ""));
            String lyricProvider = url.param("provider", url.param("lyricProvider", ""));
            String name = url.param("name", url.param("title", ""));
            String artist = url.param("artist", "");
            String album = url.param("album", "");
            sendJson(output, 200, qqLyric(mid, id, lyricProvider, name, artist, album));
            return;
        }
        if ("/api/qq/official/playlists".equals(path)) {
            sendJson(output, 200, qqOfficialPlaylists(url));
            return;
        }
        if ("/api/login/status".equals(path)) {
            sendJson(output, 200, neteaseLoginStatus());
            return;
        }
        if ("/api/qq/login/status".equals(path)) {
            sendJson(output, 200, qqLoginStatus());
            return;
        }
        if ("/api/login/cookie".equals(path)) {
            sendJson(output, 200, neteaseCookieLogin(body));
            return;
        }
        if ("/api/logout".equals(path)) {
            sendJson(output, 200, neteaseLogout());
            return;
        }
        if ("/api/login/qr/key".equals(path)) {
            sendJson(output, 200, neteaseQrKey());
            return;
        }
        if ("/api/login/qr/create".equals(path)) {
            sendJson(output, 200, neteaseQrCreate(url));
            return;
        }
        if ("/api/login/qr/check".equals(path)) {
            sendJson(output, 200, neteaseQrCheck(url));
            return;
        }
        if ("/api/qq/login/cookie".equals(path)) {
            sendJson(output, 200, qqCookieLogin(body));
            return;
        }
        if ("/api/qq/logout".equals(path)) {
            sendJson(output, 200, qqLogout());
            return;
        }
        if ("/api/user/playlists".equals(path)) {
            sendJson(output, 200, userPlaylists(url));
            return;
        }
        if ("/api/qq/user/playlists".equals(path)) {
            sendJson(output, 200, qqUserPlaylists(url));
            return;
        }
        if ("/api/playlist/tracks".equals(path)) {
            sendJson(output, 200, playlistTracks(url.param("id", ""), false));
            return;
        }
        if ("/api/qq/playlist/tracks".equals(path)) {
            sendJson(output, 200, qqPlaylistTracks(url.param("id", "")));
            return;
        }
        if ("/api/qq/playlist/create".equals(path)) {
            sendJson(output, 200, qqCloudCreatePlaylist(url, body));
            return;
        }
        if ("/api/qq/playlist/add-song".equals(path)) {
            sendJson(output, 200, qqCloudAddSongToPlaylist(url, body));
            return;
        }
        if ("/api/playlist/create".equals(path)) {
            sendJson(output, 200, createCustomPlaylist(url, body));
            return;
        }
        if ("/api/playlist/add-song".equals(path)) {
            sendJson(output, 200, addSongToCustomPlaylist(url, body));
            return;
        }
        if ("/api/song/like/check".equals(path)) {
            sendJson(output, 200, likeCheck(url));
            return;
        }
        if ("/api/song/like".equals(path)) {
            sendJson(output, 200, setSongLike(url, body));
            return;
        }
        if ("/api/song/comments".equals(path) || "/api/qq/song/comments".equals(path)) {
            sendJson(output, 200, songComments(url, path.contains("/qq/")));
            return;
        }
        if ("/api/artist/detail".equals(path)) {
            sendJson(output, 200, neteaseArtistDetail(url));
            return;
        }
        if ("/api/qq/artist/detail".equals(path)) {
            sendJson(output, 200, qqArtistDetail(url));
            return;
        }
        if ("/api/podcast/my".equals(path)) {
            JSONObject out = new JSONObject();
            out.put("loggedIn", true);
            out.put("collections", starterPodcastCollections());
            sendJson(output, 200, out);
            return;
        }
        if ("/api/podcast/my/items".equals(path)) {
            sendJson(output, 200, podcastMyItems(url));
            return;
        }
        if ("/api/podcast/hot".equals(path)) {
            sendJson(output, 200, podcastHot(url));
            return;
        }
        if ("/api/podcast/search".equals(path)) {
            sendJson(output, 200, podcastSearch(url));
            return;
        }
        if ("/api/podcast/detail".equals(path)) {
            sendJson(output, 200, podcastDetail(url));
            return;
        }
        if ("/api/podcast/programs".equals(path)) {
            sendJson(output, 200, podcastPrograms(url));
            return;
        }
        if ("/api/podcast/dj-beatmap".equals(path)) {
            sendJson(output, 200, podcastDjBeatmap());
            return;
        }
        if (path.contains("/login") || path.contains("/logout")) {
            sendJson(output, 200, loginEmpty(path));
            return;
        }
        if (path.contains("/playlist") || path.contains("/podcast") || path.contains("/comments") || path.contains("/like") || path.contains("/artist")) {
            sendJson(output, 200, emptyDomainResponse(path, url));
            return;
        }
        sendJson(output, 404, jsonError("Android API route not implemented: " + path));
    }

    private void serveAsset(OutputStream output, String method, String path) throws IOException {
        String normalized = path == null || path.equals("/") ? "/index.html" : path;
        normalized = decode(normalized);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.contains("..") || normalized.startsWith("/")) {
            sendText(output, 403, "text/plain; charset=utf-8", "Forbidden");
            return;
        }
        String assetPath = ASSET_ROOT + "/" + normalized;
        try {
            InputStream input = assets.open(assetPath);
            byte[] bytes = readAll(input);
            Map<String, String> extra = new HashMap<>();
            extra.put("Cache-Control", "no-store, no-cache, must-revalidate");
            extra.put("Pragma", "no-cache");
            sendBytes(output, 200, mimeType(assetPath), "HEAD".equals(method) ? new byte[0] : bytes, extra);
        } catch (IOException notFound) {
            if (!normalized.equals("index.html") && !normalized.contains(".")) {
                serveAsset(output, method, "/index.html");
                return;
            }
            sendText(output, 404, "text/plain; charset=utf-8", "Not Found");
        }
    }

    private JSONObject starterDiscoverHome() throws Exception {
        JSONObject out = new JSONObject();
        JSONArray daily = starterTracks("daily", false);
        out.put("loggedIn", true);
        out.put("user", androidGuestLoginStatus());
        out.put("dailySongs", daily);
        out.put("playlists", starterPlaylists(false));
        out.put("podcasts", starterPodcastCollections());
        out.put("mode", "android-starter");
        out.put("updatedAt", System.currentTimeMillis());
        return out;
    }

    private JSONObject androidGuestLoginStatus() throws Exception {
        JSONObject out = new JSONObject();
        out.put("provider", "netease");
        out.put("loggedIn", true);
        out.put("androidGuest", true);
        out.put("hasCookie", false);
        out.put("userId", "android-guest");
        out.put("nickname", "Mineradio Android");
        out.put("avatar", "");
        out.put("vipType", 0);
        out.put("vipLevel", "none");
        out.put("isVip", false);
        out.put("isSvip", false);
        out.put("vipLabel", "Android Guest");
        out.put("message", "Android starter profile; desktop cookies are not imported.");
        return out;
    }

    private JSONObject androidGuestQQStatus() throws Exception {
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        out.put("loggedIn", true);
        out.put("androidGuest", true);
        out.put("preview", false);
        out.put("stale", false);
        out.put("hasCookie", false);
        out.put("playbackKeyReady", false);
        out.put("userId", "android-qq-guest");
        out.put("uin", "android-qq-guest");
        out.put("nickname", "QQ Android Guest");
        out.put("avatar", "");
        out.put("vipType", 0);
        out.put("message", "Android starter profile; QQ cookies are not imported.");
        return out;
    }

    private JSONObject neteaseLoginStatus() throws Exception {
        if (neteaseCookie == null || neteaseCookie.trim().isEmpty()) return androidGuestLoginStatus();
        JSONObject out = new JSONObject();
        out.put("provider", "netease");
        out.put("hasCookie", true);
        out.put("androidGuest", false);
        try {
            String json = httpGet("https://music.163.com/api/nuser/account/get", neteaseHeaders());
            JSONObject root = new JSONObject(json);
            JSONObject account = root.optJSONObject("account");
            JSONObject profile = root.optJSONObject("profile");
            if (account != null || profile != null) {
                long userId = account != null ? account.optLong("id", 0) : 0;
                if (userId == 0 && profile != null) userId = profile.optLong("userId", 0);
                out.put("loggedIn", userId != 0 || profile != null);
                out.put("userId", userId == 0 ? "" : String.valueOf(userId));
                out.put("nickname", profile == null ? "网易云用户" : profile.optString("nickname", "网易云用户"));
                out.put("avatar", profile == null ? "" : profile.optString("avatarUrl", ""));
                out.put("vipType", root.optInt("vipType", 0));
                out.put("vipLevel", root.optString("vipLevel", "none"));
                out.put("isVip", root.optInt("vipType", 0) > 0);
                out.put("isSvip", false);
                out.put("vipLabel", root.optInt("vipType", 0) > 0 ? "VIP" : "none");
                out.put("profileSource", "netease-account");
                return out;
            }
            out.put("loggedIn", true);
            out.put("pendingProfile", true);
            out.put("userId", "");
            out.put("nickname", "网易云用户");
            out.put("avatar", "");
            out.put("vipType", 0);
            out.put("vipLevel", "none");
            out.put("isVip", false);
            out.put("isSvip", false);
            out.put("vipLabel", "none");
            out.put("message", "Cookie is stored, but profile was not returned by NetEase.");
            return out;
        } catch (Exception e) {
            out.put("loggedIn", true);
            out.put("pendingProfile", true);
            out.put("userId", "");
            out.put("nickname", "网易云用户");
            out.put("avatar", "");
            out.put("vipType", 0);
            out.put("vipLevel", "none");
            out.put("isVip", false);
            out.put("isSvip", false);
            out.put("vipLabel", "none");
            out.put("message", "Cookie is stored; profile refresh failed: " + e.getMessage());
            return out;
        }
    }

    private JSONObject neteaseCookieLogin(byte[] body) throws Exception {
        JSONObject bodyJson = bodyJson(body);
        String raw = firstNonEmpty(bodyJson.optString("cookie", ""), bodyJson.optString("data", ""), bodyJson.optString("text", ""));
        String normalized = normalizeCookieHeader(raw);
        JSONObject out;
        if (normalized.isEmpty() || !parseCookieString(normalized).containsKey("MUSIC_U")) {
            out = new JSONObject();
            out.put("provider", "netease");
            out.put("loggedIn", false);
            out.put("hasCookie", false);
            out.put("error", "INVALID_NETEASE_COOKIE");
            out.put("message", "网易云 cookie 缺少 MUSIC_U");
            return out;
        }
        neteaseCookie = normalized;
        persistState();
        out = neteaseLoginStatus();
        out.put("saved", true);
        out.put("hasCookie", true);
        return out;
    }

    private JSONObject neteaseQrKey() throws Exception {
        JSONObject data = new JSONObject();
        data.put("type", 3);
        HttpResult result = neteaseWeapiPost("/login/qrcode/unikey", data, false);
        JSONObject root = new JSONObject(result.body);
        String key = firstNonEmpty(root.optString("unikey", ""), root.optString("key", ""));
        JSONObject nested = root.optJSONObject("data");
        if (nested != null) key = firstNonEmpty(key, nested.optString("unikey", ""), nested.optString("key", ""));
        JSONObject out = new JSONObject();
        out.put("provider", "netease");
        out.put("code", 200);
        out.put("key", key);
        JSONObject wrapped = new JSONObject();
        wrapped.put("unikey", key);
        wrapped.put("key", key);
        out.put("data", wrapped);
        if (key.isEmpty()) {
            out.put("code", root.optInt("code", 500));
            out.put("error", "NETEASE_QR_KEY_EMPTY");
            out.put("message", root.optString("message", "网易云二维码 key 获取失败"));
        }
        return out;
    }

    private JSONObject neteaseQrCreate(RequestUrl url) throws Exception {
        String key = url.param("key", "").trim();
        JSONObject out = new JSONObject();
        out.put("provider", "netease");
        if (key.isEmpty()) {
            out.put("code", 400);
            out.put("img", "");
            out.put("url", "");
            out.put("error", "MISSING_QR_KEY");
            out.put("message", "二维码 key 为空");
            return out;
        }
        String qrUrl = "https://music.163.com/login?codekey=" + encode(key);
        JSONObject data = new JSONObject();
        data.put("qrurl", qrUrl);
        data.put("qrimg", "");
        data.put("localQr", true);
        out.put("code", 200);
        out.put("url", qrUrl);
        out.put("img", "");
        out.put("localQr", true);
        out.put("data", data);
        return out;
    }

    private JSONObject neteaseQrCheck(RequestUrl url) throws Exception {
        String key = url.param("key", "").trim();
        JSONObject out = new JSONObject();
        out.put("provider", "netease");
        if (key.isEmpty()) {
            out.put("code", 800);
            out.put("hasCookie", false);
            out.put("message", "二维码 key 为空");
            return out;
        }
        JSONObject data = new JSONObject();
        data.put("key", key);
        data.put("type", 3);
        HttpResult result = neteaseWeapiPost("/login/qrcode/client/login", data, false);
        JSONObject root = new JSONObject(result.body == null || result.body.trim().isEmpty() ? "{}" : result.body);
        int code = root.optInt("code", 0);
        String cookie = firstNonEmpty(cookieFromNeteaseQrBody(root), normalizeCookieHeader(result.setCookie));
        if (code == 803 && !parseCookieString(cookie).containsKey("MUSIC_U")) {
            result = neteaseWeapiPost("/login/qrcode/client/login", data, false);
            root = new JSONObject(result.body == null || result.body.trim().isEmpty() ? "{}" : result.body);
            code = root.optInt("code", code);
            cookie = firstNonEmpty(cookieFromNeteaseQrBody(root), normalizeCookieHeader(result.setCookie));
        }
        if (code == 803 && !cookie.isEmpty()) {
            neteaseCookie = cookie;
            persistState();
            JSONObject info = neteaseLoginStatus();
            info.put("provider", "netease");
            info.put("code", 803);
            info.put("saved", true);
            info.put("hasCookie", true);
            info.put("cookie", cookie);
            return info;
        }
        if (code == 803) {
            JSONObject info = loginInfoFromNeteaseQrBody(root);
            info.put("provider", "netease");
            info.put("code", 803);
            info.put("saved", false);
            info.put("hasCookie", false);
            return info;
        }
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String item = keys.next();
            out.put(item, root.opt(item));
        }
        if (!out.has("code")) out.put("code", code == 0 ? 801 : code);
        out.put("hasCookie", false);
        if (!out.has("message")) {
            int finalCode = out.optInt("code", 801);
            if (finalCode == 800) out.put("message", "二维码已过期");
            else if (finalCode == 802) out.put("message", "已扫码，请在手机确认");
            else out.put("message", "等待扫码");
        }
        return out;
    }

    private String cookieFromNeteaseQrBody(JSONObject root) {
        if (root == null) return "";
        String cookie = firstNonEmpty(root.optString("cookie", ""), root.optString("cookies", ""));
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            cookie = firstNonEmpty(cookie, data.optString("cookie", ""), data.optString("cookies", ""));
        }
        return normalizeCookieHeader(cookie);
    }

    private JSONObject loginInfoFromNeteaseQrBody(JSONObject root) throws Exception {
        JSONObject out = new JSONObject();
        JSONObject data = root == null ? null : root.optJSONObject("data");
        JSONObject profile = root == null ? null : root.optJSONObject("profile");
        JSONObject account = root == null ? null : root.optJSONObject("account");
        if (profile == null && data != null) profile = data.optJSONObject("profile");
        if (account == null && data != null) account = data.optJSONObject("account");
        long userId = account == null ? 0 : account.optLong("id", 0);
        if (userId == 0 && profile != null) userId = profile.optLong("userId", 0);
        out.put("loggedIn", userId != 0 || profile != null);
        out.put("pendingProfile", profile == null);
        out.put("userId", userId == 0 ? "" : String.valueOf(userId));
        out.put("nickname", firstNonEmpty(
                root == null ? "" : root.optString("nickname", ""),
                profile == null ? "" : profile.optString("nickname", ""),
                "网易云用户"));
        out.put("avatar", firstNonEmpty(
                root == null ? "" : root.optString("avatarUrl", ""),
                profile == null ? "" : profile.optString("avatarUrl", "")));
        out.put("vipType", root == null ? 0 : root.optInt("vipType", 0));
        out.put("vipLevel", root == null ? "none" : root.optString("vipLevel", "none"));
        out.put("isVip", root != null && root.optInt("vipType", 0) > 0);
        out.put("isSvip", false);
        out.put("vipLabel", root != null && root.optInt("vipType", 0) > 0 ? "VIP" : "none");
        return out;
    }

    public synchronized JSONObject saveNeteaseCookieFromNative(String rawCookie) throws Exception {
        String normalized = normalizeCookieHeader(rawCookie);
        Map<String, String> parsed = parseCookieString(normalized);
        JSONObject out = new JSONObject();
        out.put("provider", "netease");
        out.put("hasCookie", !normalized.isEmpty());
        if (normalized.isEmpty() || !parsed.containsKey("MUSIC_U")) {
            out.put("ok", false);
            out.put("loggedIn", false);
            out.put("error", "INVALID_NETEASE_COOKIE");
            out.put("message", "网易云 cookie 缺少 MUSIC_U");
            return out;
        }
        neteaseCookie = normalized;
        persistState();
        out = neteaseLoginStatus();
        out.put("ok", true);
        out.put("saved", true);
        out.put("hasCookie", true);
        return out;
    }

    private JSONObject neteaseLogout() throws Exception {
        neteaseCookie = "";
        persistState();
        JSONObject out = new JSONObject();
        out.put("provider", "netease");
        out.put("ok", true);
        out.put("loggedIn", false);
        out.put("hasCookie", false);
        return out;
    }

    private JSONObject qqLoginStatus() throws Exception {
        if (qqCookie == null || qqCookie.trim().isEmpty()) return androidGuestQQStatus();
        Map<String, String> parsed = parseCookieString(qqCookie);
        String uin = qqCookieUin(parsed);
        String key = qqCookieMusicKey(parsed);
        String playbackKey = qqCookiePlaybackKey(parsed);
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        out.put("loggedIn", !uin.isEmpty() && !key.isEmpty());
        out.put("androidGuest", false);
        out.put("preview", false);
        out.put("stale", false);
        out.put("hasCookie", true);
        out.put("playbackKeyReady", !playbackKey.isEmpty());
        out.put("userId", uin);
        out.put("uin", uin);
        out.put("nickname", uin.isEmpty() ? "QQ 音乐用户" : ("QQ " + uin.replaceFirst("^o", "")));
        out.put("avatar", "");
        out.put("vipType", 0);
        out.put("message", key.isEmpty() ? "QQ cookie is stored but lacks login key." :
                (playbackKey.isEmpty() ? "QQ cookie is stored but lacks playback key." : "QQ cookie is stored."));
        return out;
    }

    private JSONObject qqCookieLogin(byte[] body) throws Exception {
        JSONObject bodyJson = bodyJson(body);
        String raw = firstNonEmpty(bodyJson.optString("cookie", ""), bodyJson.optString("data", ""), bodyJson.optString("text", ""));
        String normalized = normalizeCookieHeader(raw);
        Map<String, String> parsed = parseCookieString(normalized);
        String uin = qqCookieUin(parsed);
        String key = qqCookieMusicKey(parsed);
        if (uin.isEmpty() || key.isEmpty()) {
            JSONObject out = new JSONObject();
            out.put("provider", "qq");
            out.put("loggedIn", false);
            out.put("hasCookie", false);
            out.put("error", "INVALID_QQ_COOKIE");
            out.put("message", "QQ cookie 缺少 uin 或有效登录票据");
            return out;
        }
        qqCookie = normalized;
        persistState();
        JSONObject out = qqLoginStatus();
        out.put("saved", true);
        return out;
    }

    public synchronized JSONObject saveQQCookieFromNative(String rawCookie) throws Exception {
        String normalized = normalizeCookieHeader(rawCookie);
        Map<String, String> parsed = parseCookieString(normalized);
        String uin = qqCookieUin(parsed);
        String key = qqCookieMusicKey(parsed);
        String playbackKey = qqCookiePlaybackKey(parsed);
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        out.put("hasCookie", !normalized.isEmpty());
        out.put("playbackKeyReady", !playbackKey.isEmpty());
        if (uin.isEmpty() || key.isEmpty()) {
            out.put("ok", false);
            out.put("loggedIn", false);
            out.put("error", "INVALID_QQ_COOKIE");
            out.put("message", "QQ cookie 缺少 uin 或有效登录票据");
            return out;
        }
        qqCookie = normalized;
        persistState();
        out = qqLoginStatus();
        out.put("ok", true);
        out.put("saved", true);
        return out;
    }

    private JSONObject qqLogout() throws Exception {
        qqCookie = "";
        persistState();
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        out.put("ok", true);
        out.put("loggedIn", false);
        out.put("hasCookie", false);
        return out;
    }

    private JSONObject qqUserPlaylists(RequestUrl url) throws Exception {
        JSONObject status = qqLoginStatus();
        if (!status.optBoolean("hasCookie", false) || !status.optBoolean("playbackKeyReady", false)) {
            JSONObject out = new JSONObject();
            out.put("provider", "qq");
            out.put("loggedIn", true);
            out.put("androidGuest", true);
            out.put("userId", "android-qq-guest");
            out.put("playlists", starterPlaylists(true));
            return out;
        }
        String uin = status.optString("uin", status.optString("userId", ""));
        JSONArray playlists = new JSONArray();
        try {
            int limit = clampInt(url.param("limit", "120"), 20, 220, 120);
            JSONObject created = qqGetJson("https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss",
                    queryMap(new String[][]{
                            {"hostUin", "0"},
                            {"hostuin", uin},
                            {"sin", "0"},
                            {"size", String.valueOf(limit)},
                            {"g_tk", "5381"},
                            {"loginUin", uin},
                            {"format", "json"},
                            {"inCharset", "utf8"},
                            {"outCharset", "utf-8"},
                            {"notice", "0"},
                            {"platform", "yqq.json"},
                            {"needNewCode", "0"}
                    }),
                    "https://y.qq.com/n/ryqq/profile");
            JSONObject createdData = created.optJSONObject("data");
            JSONArray createdList = createdData == null ? null : createdData.optJSONArray("disslist");
            appendQQPlaylists(playlists, createdList, "created");
        } catch (Exception e) {
            Log.w(TAG, "qq created playlists failed: " + e.getMessage());
        }
        try {
            JSONObject collected = qqGetJson("https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg",
                    queryMap(new String[][]{
                            {"ct", "20"},
                            {"cid", "205360956"},
                            {"userid", uin},
                            {"reqtype", "3"},
                            {"sin", "0"},
                            {"ein", "120"}
                    }),
                    "https://y.qq.com/n/ryqq/profile");
            JSONObject collectedData = collected.optJSONObject("data");
            JSONArray collectedList = collectedData == null ? null : collectedData.optJSONArray("cdlist");
            appendQQPlaylists(playlists, collectedList, "collect");
        } catch (Exception e) {
            Log.w(TAG, "qq collected playlists failed: " + e.getMessage());
        }
        if (playlists.length() == 0) playlists = starterPlaylists(true);
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        out.put("loggedIn", true);
        out.put("hasCookie", true);
        out.put("playbackKeyReady", true);
        out.put("userId", uin);
        out.put("playlists", dedupeQQPlaylists(playlists));
        return out;
    }

    private JSONObject qqPlaylistTracks(String id) throws Exception {
        String pid = id == null ? "" : id.trim();
        if (pid.isEmpty()) {
            JSONObject out = new JSONObject();
            out.put("provider", "qq");
            out.put("loggedIn", true);
            out.put("error", "Missing QQ playlist id");
            out.put("tracks", new JSONArray());
            return out;
        }
        JSONObject status = qqLoginStatus();
        if (pid.startsWith("qq-")) {
            return starterPlaylistTracks(pid, true);
        }
        try {
            JSONObject detail = qqGetJson("https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg",
                    queryMap(new String[][]{
                            {"type", "1"},
                            {"utf8", "1"},
                            {"disstid", pid},
                            {"loginUin", firstNonEmpty(status.optString("uin", ""), status.optString("userId", ""), "0")},
                            {"format", "json"},
                            {"inCharset", "utf8"},
                            {"outCharset", "utf-8"},
                            {"notice", "0"},
                            {"platform", "yqq.json"},
                            {"needNewCode", "0"}
                    }),
                    "https://y.qq.com/n/yqq/playlist");
            JSONArray cdlist = detail.optJSONArray("cdlist");
            JSONObject rawPlaylist = cdlist == null || cdlist.length() == 0 ? null : cdlist.optJSONObject(0);
            JSONArray rawTracks = rawPlaylist == null ? null : rawPlaylist.optJSONArray("songlist");
            JSONArray tracks = new JSONArray();
            if (rawTracks != null) {
                for (int i = 0; i < rawTracks.length(); i++) {
                    JSONObject song = mapQQPlaylistTrack(rawTracks.optJSONObject(i));
                    if (song != null && !song.optString("name", "").isEmpty()) tracks.put(song);
                }
            }
            JSONObject playlist = new JSONObject();
            playlist.put("provider", "qq");
            playlist.put("id", pid);
            playlist.put("name", rawPlaylist == null ? "" : firstNonEmpty(rawPlaylist.optString("dissname", ""), rawPlaylist.optString("diss_name", ""), rawPlaylist.optString("name", "")));
            playlist.put("cover", rawPlaylist == null ? "" : firstNonEmpty(rawPlaylist.optString("logo", ""), rawPlaylist.optString("diss_cover", "")));
            playlist.put("trackCount", tracks.length());
            JSONObject out = new JSONObject();
            out.put("provider", "qq");
            out.put("loggedIn", status.optBoolean("loggedIn", false));
            out.put("hasCookie", status.optBoolean("hasCookie", false));
            out.put("playlist", playlist);
            out.put("tracks", tracks);
            out.put("remote", true);
            return out;
        } catch (Exception e) {
            JSONObject out = starterPlaylistTracks(pid, true);
            out.put("remoteError", e.getMessage());
            return out;
        }
    }

    private JSONObject qqOfficialPlaylists(RequestUrl url) throws Exception {
        int pageNo = clampInt(url.param("page", url.param("pageNo", "1")), 1, 100, 1);
        int pageSize = clampInt(url.param("limit", url.param("pageSize", "24")), 6, 60, 24);
        int category = clampInt(url.param("category", url.param("id", "3317")), 1, 99999999, 3317);
        JSONObject payload = new JSONObject();
        payload.put("comm", new JSONObject().put("ct", 24));
        payload.put("playlist", new JSONObject()
                .put("module", "playlist.PlayListPlazaServer")
                .put("method", "get_playlist_by_category")
                .put("param", new JSONObject()
                        .put("id", category)
                        .put("titleid", category)
                        .put("curPage", pageNo)
                        .put("size", pageSize)
                        .put("order", 5)));
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        out.put("category", category);
        out.put("pageNo", pageNo);
        out.put("pageSize", pageSize);
        JSONArray playlists = new JSONArray();
        try {
            String json = httpPost("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json", payload.toString(), "application/json; charset=UTF-8", qqHeaders());
            JSONObject root = new JSONObject(json);
            JSONObject block = root.optJSONObject("playlist");
            JSONObject data = block == null ? null : block.optJSONObject("data");
            JSONArray raw = data == null ? null : data.optJSONArray("v_playlist");
            if (raw != null) appendQQPlaylists(playlists, raw, "official");
            out.put("total", data == null ? playlists.length() : data.optInt("total", playlists.length()));
        } catch (Exception e) {
            Log.w(TAG, "qq official playlists failed: " + e.getMessage());
            out.put("error", e.getMessage());
            appendQQPlaylists(playlists, starterPlaylists(true), "official");
            out.put("fallback", "starter");
            out.put("total", playlists.length());
        }
        out.put("playlists", dedupeQQPlaylists(playlists));
        return out;
    }

    private JSONObject userPlaylists(RequestUrl url) throws Exception {
        JSONObject info = neteaseLoginStatus();
        String userId = info.optString("userId", "");
        JSONArray playlists = new JSONArray();
        boolean remoteTried = false;
        if (!userId.isEmpty() && neteaseCookie != null && !neteaseCookie.trim().isEmpty()) {
            remoteTried = true;
            try {
                int limit = clampInt(url.param("limit", "60"), 1, 120, 60);
                String endpoint = "https://music.163.com/api/user/playlist?uid=" + encode(userId) + "&limit=" + limit + "&offset=0";
                String json = httpGet(endpoint, neteaseHeaders());
                JSONObject root = new JSONObject(json);
                JSONArray raw = root.optJSONArray("playlist");
                if (raw != null) {
                    for (int i = 0; i < raw.length(); i++) {
                        JSONObject playlist = mapNeteasePlaylist(raw.optJSONObject(i));
                        if (playlist != null) playlists.put(playlist);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "user playlists failed: " + e.getMessage());
            }
        }
        if (playlists.length() == 0 && !remoteTried) {
            playlists = combinedUserPlaylists();
        } else {
            appendCustomPlaylists(playlists);
            if (playlists.length() == 0) playlists = combinedUserPlaylists();
        }
        JSONObject out = new JSONObject();
        out.put("loggedIn", true);
        out.put("hasCookie", neteaseCookie != null && !neteaseCookie.trim().isEmpty());
        out.put("androidGuest", info.optBoolean("androidGuest", false));
        out.put("userId", userId.isEmpty() ? "android-guest" : userId);
        out.put("playlists", playlists);
        return out;
    }

    private JSONObject mapNeteasePlaylist(JSONObject raw) throws Exception {
        if (raw == null) return null;
        JSONObject out = new JSONObject();
        JSONObject creator = raw.optJSONObject("creator");
        out.put("id", String.valueOf(raw.optLong("id", safeLong(raw.optString("id", ""), 0))));
        out.put("name", raw.optString("name", ""));
        out.put("creator", creator == null ? "" : creator.optString("nickname", ""));
        out.put("creatorId", creator == null ? "" : String.valueOf(creator.optLong("userId", 0)));
        out.put("subscribed", raw.optBoolean("subscribed", false));
        out.put("trackCount", raw.optInt("trackCount", raw.optJSONArray("tracks") == null ? 0 : raw.optJSONArray("tracks").length()));
        out.put("playCount", raw.optLong("playCount", 0));
        out.put("specialType", raw.optInt("specialType", 0));
        out.put("provider", "netease");
        out.put("source", "netease");
        out.put("cover", raw.optString("coverImgUrl", raw.optString("picUrl", "")));
        return out;
    }

    private void appendCustomPlaylists(JSONArray out) throws Exception {
        if (out == null) return;
        synchronized (customPlaylists) {
            for (int i = 0; i < customPlaylists.size(); i++) {
                out.put(customPlaylistMeta(customPlaylists.get(i)));
            }
        }
    }

    private void appendQQPlaylists(JSONArray target, JSONArray rawList, String kind) throws Exception {
        if (target == null || rawList == null) return;
        for (int i = 0; i < rawList.length(); i++) {
            JSONObject playlist = mapQQPlaylist(rawList.optJSONObject(i), kind);
            if (playlist != null && !playlist.optString("id", "").isEmpty() && !playlist.optString("name", "").isEmpty()) {
                target.put(playlist);
            }
        }
    }

    private JSONArray dedupeQQPlaylists(JSONArray input) throws Exception {
        JSONArray out = new JSONArray();
        Map<String, Boolean> seen = new HashMap<>();
        if (input == null) return out;
        for (int i = 0; i < input.length(); i++) {
            JSONObject playlist = input.optJSONObject(i);
            if (playlist == null) continue;
            String id = playlist.optString("id", "");
            if (id.isEmpty() || seen.containsKey(id)) continue;
            seen.put(id, true);
            out.put(playlist);
        }
        return out;
    }

    private JSONObject mapQQPlaylist(JSONObject raw, String kind) throws Exception {
        if (raw == null) return null;
        JSONObject creator = raw.optJSONObject("creator");
        if (creator == null) creator = raw.optJSONObject("owner");
        String id = firstNonEmpty(raw.optString("dissid", ""), raw.optString("tid", ""), raw.optString("dirid", ""), raw.optString("id", ""), raw.optString("diss_id", ""), raw.optString("disstid", ""));
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        out.put("source", "qq");
        out.put("id", id);
        out.put("name", firstNonEmpty(raw.optString("diss_name", ""), raw.optString("name", ""), raw.optString("title", ""), raw.optString("dissname", "")));
        out.put("cover", firstNonEmpty(raw.optString("diss_cover", ""), raw.optString("logo", ""), raw.optString("picurl", ""), raw.optString("cover", ""),
                raw.optString("cover_url_big", ""), raw.optString("cover_url_medium", ""), raw.optString("cover_url", ""), raw.optString("pic_url", "")));
        out.put("trackCount", firstPositiveInt(raw, new String[]{"song_cnt", "songnum", "total_song_num", "song_count"}));
        out.put("playCount", firstPositiveLong(raw, new String[]{"listen_num", "visitnum", "play_count"}));
        out.put("creator", firstNonEmpty(raw.optString("hostname", ""), raw.optString("nick", ""), raw.optString("creator_name", ""),
                creator == null ? "" : creator.optString("nick", ""),
                creator == null ? "" : creator.optString("name", ""),
                raw.optString("creator", ""), "official".equals(kind) ? "QQ 音乐官方" : "QQ 音乐"));
        out.put("subscribed", "collect".equals(kind));
        out.put("official", "official".equals(kind));
        out.put("specialType", 0);
        return out;
    }

    private JSONObject mapQQPlaylistTrack(JSONObject raw) throws Exception {
        if (raw == null) return null;
        JSONObject track = firstObject(raw, new String[]{"track_info", "songInfo", "songinfo", "song"});
        if (track == null) track = raw;
        JSONObject album = track.optJSONObject("album");
        if (album == null) album = new JSONObject();
        JSONArray artists = mapQQArtists(firstArray(track, new String[]{"singer", "singers"}));
        String mid = firstNonEmpty(track.optString("mid", ""), track.optString("songmid", ""), raw.optString("mid", ""), raw.optString("songmid", ""));
        String albumMid = firstNonEmpty(album.optString("mid", ""), track.optString("albummid", ""), raw.optString("albummid", ""));
        String mediaMid = "";
        JSONObject file = track.optJSONObject("file");
        if (file != null) mediaMid = file.optString("media_mid", "");
        mediaMid = firstNonEmpty(mediaMid, track.optString("strMediaMid", ""), track.optString("media_mid", ""), raw.optString("strMediaMid", ""), mid);
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        out.put("source", "qq");
        out.put("type", "qq");
        out.put("id", firstNonEmpty(mid, track.optString("id", ""), track.optString("songid", ""), raw.optString("id", ""), raw.optString("songid", "")));
        out.put("qqId", firstNonEmpty(track.optString("id", ""), track.optString("songid", ""), raw.optString("id", ""), raw.optString("songid", "")));
        out.put("mid", mid);
        out.put("songmid", mid);
        out.put("mediaMid", mediaMid);
        out.put("name", firstNonEmpty(track.optString("name", ""), track.optString("songname", ""), raw.optString("songname", "")));
        out.put("artist", joinArtistNames(artists));
        out.put("artists", artists);
        JSONObject firstArtist = artists.length() > 0 ? artists.optJSONObject(0) : null;
        if (firstArtist != null) {
            out.put("artistId", firstNonEmpty(firstArtist.optString("id", ""), firstArtist.optString("mid", "")));
            out.put("artistMid", firstArtist.optString("mid", ""));
        }
        out.put("album", firstNonEmpty(album.optString("name", ""), album.optString("title", ""), track.optString("albumname", ""), raw.optString("albumname", "")));
        out.put("albumMid", albumMid);
        out.put("cover", albumMid.isEmpty() ? "" : "https://y.qq.com/music/photo_new/T002R300x300M000" + albumMid + ".jpg?max_age=2592000");
        long interval = firstPositiveLong(track, new String[]{"interval"});
        if (interval == 0) interval = firstPositiveLong(raw, new String[]{"interval"});
        out.put("duration", interval * 1000L);
        out.put("fee", 0);
        return out;
    }

    private JSONArray mapQQArtists(JSONArray rawArtists) throws Exception {
        JSONArray out = new JSONArray();
        if (rawArtists == null) return out;
        for (int i = 0; i < rawArtists.length(); i++) {
            JSONObject raw = rawArtists.optJSONObject(i);
            if (raw == null) continue;
            JSONObject artist = new JSONObject();
            artist.put("id", raw.optString("id", ""));
            artist.put("mid", raw.optString("mid", ""));
            artist.put("name", firstNonEmpty(raw.optString("name", ""), raw.optString("title", "")));
            if (!artist.optString("name", "").isEmpty()) out.put(artist);
        }
        return out;
    }

    private String qqSingerAvatar(String mid, int size) {
        String clean = mid == null ? "" : mid.trim();
        if (clean.isEmpty()) return "";
        int target = Math.max(150, Math.min(500, size));
        return "https://y.qq.com/music/photo_new/T001R" + target + "x" + target + "M000" + clean + ".jpg?max_age=2592000";
    }

    private JSONArray starterPlaylists(boolean qq) throws Exception {
        JSONArray out = new JSONArray();
        if (qq) {
            out.put(starterPlaylist("qq-galaxy", "QQ 银河卡片测试", "QQ 音乐", true, 8, 131200, true));
            out.put(starterPlaylist("qq-stage", "QQ 舞台热歌测试", "QQ 音乐", true, 8, 98200, true));
            return out;
        }
        out.put(starterPlaylist("neon", "霓虹粒子电台", "Mineradio Android", false, 10, 186000, false));
        out.put(starterPlaylist("rain", "雨夜歌词舞台", "Mineradio Android", true, 10, 124000, false));
        out.put(starterPlaylist("city", "城市巡航歌单", "Mineradio Android", false, 10, 214000, false));
        return out;
    }

    private JSONObject starterPlaylist(String id, String name, String creator, boolean subscribed, int trackCount, int playCount, boolean qq) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray tracks = starterTracks(id, qq);
        out.put("id", id);
        out.put("name", name);
        out.put("creator", creator);
        out.put("subscribed", subscribed);
        out.put("trackCount", tracks.length() > 0 ? tracks.length() : trackCount);
        out.put("playCount", playCount);
        out.put("specialType", 0);
        out.put("provider", qq ? "qq" : "netease");
        out.put("source", qq ? "qq" : "netease");
        JSONObject first = tracks.length() > 0 ? tracks.optJSONObject(0) : null;
        out.put("cover", first == null ? "" : first.optString("cover", ""));
        return out;
    }

    private JSONObject starterPlaylistTracks(String id, boolean qq) throws Exception {
        JSONArray tracks = starterTracks(id, qq);
        JSONObject playlist = starterPlaylistMeta(id, qq, tracks);
        JSONObject out = new JSONObject();
        out.put("provider", qq ? "qq" : "netease");
        out.put("loggedIn", true);
        out.put("playlist", playlist);
        out.put("tracks", tracks);
        return out;
    }

    private JSONObject starterPlaylistMeta(String id, boolean qq, JSONArray tracks) throws Exception {
        String name;
        String creator = qq ? "QQ 音乐" : "Mineradio Android";
        boolean subscribed = false;
        int playCount = 88000;
        if (qq) {
            name = "qq-stage".equals(id) ? "QQ 舞台热歌测试" : "QQ 银河卡片测试";
            subscribed = true;
            playCount = "qq-stage".equals(id) ? 98200 : 131200;
        } else if ("rain".equals(id)) {
            name = "雨夜歌词舞台";
            subscribed = true;
            playCount = 124000;
        } else if ("city".equals(id)) {
            name = "城市巡航歌单";
            playCount = 214000;
        } else if ("daily".equals(id)) {
            name = "Android 每日推荐";
            playCount = 168000;
        } else {
            name = "霓虹粒子电台";
            playCount = 186000;
        }
        JSONObject first = tracks.length() > 0 ? tracks.optJSONObject(0) : null;
        JSONObject playlist = new JSONObject();
        playlist.put("id", id);
        playlist.put("name", name);
        playlist.put("creator", creator);
        playlist.put("subscribed", subscribed);
        playlist.put("trackCount", tracks.length());
        playlist.put("playCount", playCount);
        playlist.put("cover", first == null ? "" : first.optString("cover", ""));
        playlist.put("provider", qq ? "qq" : "netease");
        return playlist;
    }

    private JSONArray starterTracks(String playlistId, boolean qq) throws Exception {
        String key = (qq ? "qq:" : "ne:") + (playlistId == null ? "" : playlistId);
        synchronized (starterTrackCache) {
            JSONArray cached = starterTrackCache.get(key);
            if (cached != null) return cloneArray(cached);
        }
        JSONArray tracks = new JSONArray();
        String[] seeds = starterSeeds(playlistId, qq);
        for (String seed : seeds) {
            JSONArray found = qq ? searchQQ(seed, 2, 1).optJSONArray("songs") : searchNetease(seed, 3);
            if (found == null) found = new JSONArray();
            for (int i = 0; i < found.length() && tracks.length() < 10; i++) {
                JSONObject song = found.optJSONObject(i);
                if (song != null && song.opt("id") != null && !song.optString("name").isEmpty()) {
                    tracks.put(song);
                    break;
                }
            }
        }
        int desired = "daily".equals(playlistId) ? 6 : (qq ? 8 : 10);
        if (tracks.length() < desired) appendFallbackTracks(tracks, fallbackStarterTracks(qq), desired);
        synchronized (starterTrackCache) {
            starterTrackCache.put(key, cloneArray(tracks));
        }
        return cloneArray(tracks);
    }

    private String[] starterSeeds(String playlistId, boolean qq) {
        if (qq) {
            if ("qq-stage".equals(playlistId)) {
                return new String[]{"林俊杰 江南", "五月天 突然好想你", "孙燕姿 遇见", "王菲 红豆", "陈奕迅 十年", "G.E.M. 光年之外", "告五人 爱人错过", "周杰伦 稻香"};
            }
            return new String[]{"周杰伦 晴天", "陶喆 普通朋友", "王力宏 唯一", "蔡健雅 beautiful love", "张惠妹 听海", "梁静茹 勇气", "陈奕迅 富士山下", "林宥嘉 说谎"};
        }
        if ("rain".equals(playlistId)) {
            return new String[]{"雨天 孙燕姿", "下雨天 南拳妈妈", "可惜没如果 林俊杰", "慢冷 梁静茹", "红豆 王菲", "夜曲 周杰伦", "修炼爱情 林俊杰", "阴天 莫文蔚", "突然好想你 五月天", "富士山下 陈奕迅"};
        }
        if ("city".equals(playlistId)) {
            return new String[]{"city pop", "night drive", "霓虹", "午夜电台", "落日飞车", "deca joins", "告五人 爱人错过", "椅子乐团", "万能青年旅店", "草东没有派对"};
        }
        if ("daily".equals(playlistId)) {
            return new String[]{"稻香 周杰伦", "江南 林俊杰", "遇见 孙燕姿", "十年 陈奕迅", "光年之外 邓紫棋", "晴天 周杰伦"};
        }
        return new String[]{"稻香 周杰伦", "晴天 周杰伦", "江南 林俊杰", "遇见 孙燕姿", "十年 陈奕迅", "唯一 王力宏", "普通朋友 陶喆", "爱人错过 告五人", "慢慢 喜欢你 莫文蔚", "光年之外 邓紫棋"};
    }

    private JSONArray fallbackStarterTracks(boolean qq) throws Exception {
        JSONArray out = new JSONArray();
        if (qq) {
            out.put(fallbackQQSong("003OUlho2HcRHC", "QQ 测试歌曲", "QQ 音乐", ""));
            out.put(fallbackQQSong("001Qu4I30eVFYb", "银河漫游", "QQ 音乐", ""));
            out.put(fallbackQQSong("002mWVx72p8Ugp", "舞台光束", "QQ 音乐", ""));
            out.put(fallbackQQSong("000xogLP35ayzS", "夜航卡片", "QQ 音乐", ""));
            out.put(fallbackQQSong("004Z8Ihr0JIu5s", "粒子回声", "QQ 音乐", ""));
            out.put(fallbackQQSong("0039MnYb0qxYhV", "水晶电台", "QQ 音乐", ""));
            out.put(fallbackQQSong("002J4UUk29y8BY", "星云频段", "QQ 音乐", ""));
            out.put(fallbackQQSong("001o0GrX3eRaLy", "浮光歌单", "QQ 音乐", ""));
            return out;
        }
        out.put(fallbackNeteaseSong(3357698666L, "稻香", "周杰伦", "Android Starter"));
        out.put(fallbackNeteaseSong(186016L, "晴天", "周杰伦", "Android Starter"));
        out.put(fallbackNeteaseSong(108242L, "江南", "林俊杰", "Android Starter"));
        out.put(fallbackNeteaseSong(287035L, "遇见", "孙燕姿", "Android Starter"));
        out.put(fallbackNeteaseSong(66842L, "十年", "陈奕迅", "Android Starter"));
        out.put(fallbackNeteaseSong(25638273L, "夜曲", "周杰伦", "Android Starter"));
        out.put(fallbackNeteaseSong(108390L, "修炼爱情", "林俊杰", "Android Starter"));
        out.put(fallbackNeteaseSong(167876L, "红豆", "王菲", "Android Starter"));
        out.put(fallbackNeteaseSong(525278524L, "光年之外", "G.E.M. 邓紫棋", "Android Starter"));
        out.put(fallbackNeteaseSong(1368754688L, "爱人错过", "告五人", "Android Starter"));
        return out;
    }

    private void appendFallbackTracks(JSONArray target, JSONArray fallback, int desired) {
        if (target == null || fallback == null) return;
        for (int i = 0; i < fallback.length() && target.length() < desired; i++) {
            JSONObject song = fallback.optJSONObject(i);
            if (song == null || containsTrack(target, song)) continue;
            target.put(song);
        }
    }

    private boolean containsTrack(JSONArray target, JSONObject song) {
        String id = song == null ? "" : song.optString("id", "");
        String mid = song == null ? "" : song.optString("mid", "");
        String name = song == null ? "" : song.optString("name", "");
        for (int i = 0; i < target.length(); i++) {
            JSONObject existing = target.optJSONObject(i);
            if (existing == null) continue;
            if (!id.isEmpty() && id.equals(existing.optString("id", ""))) return true;
            if (!mid.isEmpty() && mid.equals(existing.optString("mid", ""))) return true;
            if (!name.isEmpty() && name.equals(existing.optString("name", "")) && song.optString("artist", "").equals(existing.optString("artist", ""))) return true;
        }
        return false;
    }

    private JSONObject fallbackNeteaseSong(long id, String name, String artist, String album) throws Exception {
        JSONObject out = new JSONObject();
        out.put("provider", "netease");
        out.put("source", "netease");
        out.put("type", "song");
        out.put("id", id);
        out.put("name", name);
        out.put("artist", artist);
        out.put("artists", new JSONArray().put(new JSONObject().put("id", 0).put("name", artist)));
        out.put("artistId", 0);
        out.put("album", album);
        out.put("cover", "");
        out.put("duration", 0);
        out.put("fee", 0);
        return out;
    }

    private JSONObject fallbackQQSong(String mid, String name, String artist, String albumMid) throws Exception {
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        out.put("source", "qq");
        out.put("type", "song");
        out.put("id", mid);
        out.put("mid", mid);
        out.put("songmid", mid);
        out.put("mediaMid", mid);
        out.put("name", name);
        out.put("artist", artist);
        out.put("artists", new JSONArray());
        out.put("album", "Android Starter");
        out.put("cover", albumMid == null || albumMid.isEmpty() ? "" : "https://y.qq.com/music/photo_new/T002R300x300M000" + albumMid + ".jpg");
        out.put("duration", 0);
        return out;
    }

    private JSONArray starterPodcastCollections() throws Exception {
        JSONArray out = new JSONArray();
        JSONObject collection = new JSONObject();
        collection.put("key", "android-weather");
        collection.put("title", "天气电台预设");
        collection.put("subtitle", "Android starter podcasts");
        collection.put("sub", "Android starter podcasts");
        JSONArray radios = starterPodcastRadios();
        collection.put("count", radios.length());
        collection.put("cover", "");
        collection.put("itemType", "radio");
        out.put(collection);
        JSONObject liked = new JSONObject();
        liked.put("key", "liked");
        liked.put("title", "喜欢的声音");
        liked.put("subtitle", "Android playable podcast voices");
        liked.put("sub", "Android playable podcast voices");
        JSONArray voices = starterPodcastPrograms("android-weather", 6);
        liked.put("count", voices.length());
        liked.put("cover", "");
        liked.put("itemType", "voice");
        out.put(liked);
        return out;
    }

    private JSONArray cloneArray(JSONArray input) throws Exception {
        return new JSONArray(input == null ? "[]" : input.toString());
    }

    private JSONObject defaultLocation(String city) throws Exception {
        JSONObject location = new JSONObject();
        location.put("name", city == null || city.trim().isEmpty() ? "Shanghai" : city.trim());
        location.put("country", "China");
        location.put("latitude", 31.2304);
        location.put("longitude", 121.4737);
        location.put("timezone", "Asia/Shanghai");
        return location;
    }

    private JSONObject weatherRadio(RequestUrl url) throws Exception {
        String city = url.param("city", url.param("q", "Shanghai"));
        JSONObject weather = new JSONObject();
        weather.put("label", "Clear");
        weather.put("temperature", 24);
        weather.put("apparentTemperature", 24);
        weather.put("humidity", 62);
        weather.put("location", defaultLocation(city));
        JSONObject mood = new JSONObject();
        mood.put("key", "clear");
        mood.put("name", "Clear");
        weather.put("mood", mood);

        JSONArray seeds = new JSONArray();
        seeds.put("city pop");
        seeds.put("rainy r&b");
        seeds.put("late night drive");
        JSONArray songs = searchNetease("city pop", 8);

        JSONObject radio = new JSONObject();
        radio.put("title", city + " Weather Radio");
        radio.put("subtitle", "Android local weather profile");
        radio.put("seedQueries", seeds);
        radio.put("songs", songs);

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("weather", weather);
        out.put("radio", radio);
        out.put("updatedAt", System.currentTimeMillis());
        return out;
    }

    private JSONObject neteaseSongUrl(String id, String quality) throws Exception {
        JSONObject out = new JSONObject();
        out.put("provider", "netease");
        out.put("requestedQuality", quality);
        if (id == null || id.trim().isEmpty()) {
            out.put("url", "");
            out.put("playable", false);
            out.put("error", "MISSING_ID");
            return out;
        }
        int br = neteaseBrForQuality(quality);
        try {
            String endpoint = "https://music.163.com/api/song/enhance/player/url?ids="
                    + encode("[" + id + "]") + "&br=" + br;
            String json = httpGet(endpoint, neteaseHeaders());
            JSONObject root = new JSONObject(json);
            JSONArray data = root.optJSONArray("data");
            JSONObject item = data == null || data.length() == 0 ? null : data.optJSONObject(0);
            String resolved = item == null ? "" : item.optString("url", "");
            if (!resolved.isEmpty()) {
                out.put("url", resolved);
                out.put("trial", item.opt("freeTrialInfo") != null && !JSONObject.NULL.equals(item.opt("freeTrialInfo")));
                out.put("playable", true);
                out.put("level", item.optString("level", quality));
                out.put("quality", item.optString("level", quality));
                out.put("br", item.optLong("br", br));
                out.put("size", item.optLong("size", 0));
                out.put("type", item.optString("type", ""));
                out.put("fee", item.optInt("fee", 0));
                return out;
            }
            out.put("upstreamCode", item == null ? root.optInt("code", 0) : item.optInt("code", 0));
        } catch (Exception e) {
            out.put("upstreamError", e.getMessage());
        }
        out.put("url", "https://music.163.com/song/media/outer/url?id=" + encode(id) + ".mp3");
        out.put("trial", false);
        out.put("playable", true);
        out.put("level", quality);
        out.put("quality", quality);
        out.put("fallback", "outer-url");
        return out;
    }

    private JSONObject remoteNeteasePlaylistTracks(String id) throws Exception {
        JSONObject out = new JSONObject();
        out.put("provider", "netease");
        out.put("loggedIn", true);
        try {
            String endpoint = "https://music.163.com/api/v6/playlist/detail?id=" + encode(id) + "&n=1000&s=0";
            String json = httpGet(endpoint, neteaseHeaders());
            JSONObject root = new JSONObject(json);
            JSONObject rawPlaylist = root.optJSONObject("playlist");
            if (rawPlaylist == null) {
                out.put("playlist", new JSONObject().put("id", id).put("name", ""));
                out.put("tracks", new JSONArray());
                return out;
            }
            JSONArray rawTracks = rawPlaylist.optJSONArray("tracks");
            JSONArray tracks = new JSONArray();
            if (rawTracks != null) {
                for (int i = 0; i < rawTracks.length(); i++) {
                    JSONObject song = rawTracks.optJSONObject(i);
                    if (song != null) tracks.put(mapNeteaseSong(song));
                }
            }
            out.put("playlist", mapNeteasePlaylist(rawPlaylist));
            out.put("tracks", tracks);
            out.put("remote", true);
            return out;
        } catch (Exception e) {
            out.put("playlist", new JSONObject().put("id", id).put("name", ""));
            out.put("tracks", new JSONArray());
            out.put("error", e.getMessage());
            return out;
        }
    }

    private JSONArray combinedUserPlaylists() throws Exception {
        JSONArray out = starterPlaylists(false);
        synchronized (customPlaylists) {
            for (int i = 0; i < out.length(); i++) {
                JSONObject playlist = out.optJSONObject(i);
                if (playlist == null) continue;
                JSONArray additions = customPlaylistTracks.get(playlist.optString("id", ""));
                if (additions != null && additions.length() > 0) {
                    playlist.put("trackCount", playlist.optInt("trackCount", 0) + additions.length());
                    if (playlist.optString("cover", "").isEmpty()) {
                        JSONObject first = additions.optJSONObject(0);
                        if (first != null) playlist.put("cover", first.optString("cover", ""));
                    }
                }
            }
            for (int i = 0; i < customPlaylists.size(); i++) {
                out.put(customPlaylistMeta(customPlaylists.get(i)));
            }
        }
        return out;
    }

    private JSONObject playlistTracks(String id, boolean qq) throws Exception {
        if (!qq && isCustomPlaylistId(id)) {
            JSONArray tracks;
            synchronized (customPlaylists) {
                JSONArray stored = customPlaylistTracks.get(id);
                tracks = stored == null ? new JSONArray() : cloneArray(stored);
            }
            JSONObject playlist = customPlaylistMeta(findCustomPlaylist(id));
            JSONObject out = new JSONObject();
            out.put("provider", "netease");
            out.put("loggedIn", true);
            out.put("playlist", playlist);
            out.put("tracks", tracks);
            return out;
        }
        if (!qq && isNumericString(id)) {
            JSONObject remote = remoteNeteasePlaylistTracks(id);
            JSONArray remoteTracks = remote.optJSONArray("tracks");
            if (remoteTracks != null && remoteTracks.length() > 0) return remote;
        }
        JSONObject out = starterPlaylistTracks(id, qq);
        if (!qq) {
            JSONArray additions;
            synchronized (customPlaylists) {
                JSONArray stored = customPlaylistTracks.get(id);
                additions = stored == null ? new JSONArray() : cloneArray(stored);
            }
            if (additions.length() > 0) {
                JSONArray tracks = out.optJSONArray("tracks");
                if (tracks == null) tracks = new JSONArray();
                for (int i = 0; i < additions.length(); i++) {
                    JSONObject song = additions.optJSONObject(i);
                    if (song != null && !containsTrack(tracks, song)) tracks.put(song);
                }
                out.put("tracks", tracks);
                JSONObject playlist = out.optJSONObject("playlist");
                if (playlist != null) playlist.put("trackCount", tracks.length());
            }
        }
        return out;
    }

    private JSONObject createCustomPlaylist(RequestUrl url, byte[] body) throws Exception {
        JSONObject bodyJson = bodyJson(body);
        String name = firstNonEmpty(bodyJson.optString("name", ""), url.param("name", ""));
        String privacy = firstNonEmpty(bodyJson.optString("privacy", ""), url.param("privacy", "0"));
        JSONObject out = new JSONObject();
        if (name.trim().isEmpty()) {
            out.put("loggedIn", true);
            out.put("error", "Missing playlist name");
            return out;
        }
        if (hasNeteaseMusicU()) {
            try {
                return neteaseCloudCreatePlaylist(name.trim(), privacy);
            } catch (Exception e) {
                Log.w(TAG, "netease cloud playlist create failed: " + e.getMessage());
                out.put("cloudFallback", true);
                out.put("cloudError", e.getMessage());
            }
        }
        JSONObject playlist = new JSONObject();
        synchronized (customPlaylists) {
            String id = "android-custom-" + customPlaylistSeq++;
            playlist.put("id", id);
            playlist.put("name", name.trim());
            playlist.put("creator", "Mineradio Android");
            playlist.put("subscribed", false);
            playlist.put("trackCount", 0);
            playlist.put("playCount", 0);
            playlist.put("specialType", 0);
            playlist.put("provider", "netease");
            playlist.put("source", "netease");
            playlist.put("cover", "");
            customPlaylists.add(new JSONObject(playlist.toString()));
            customPlaylistTracks.put(id, new JSONArray());
        }
        persistState();
        out.put("loggedIn", true);
        out.put("androidLocal", true);
        if (!out.has("cloudFallback")) out.put("cloudFallback", false);
        out.put("playlist", playlist);
        return out;
    }

    private JSONObject addSongToCustomPlaylist(RequestUrl url, byte[] body) throws Exception {
        JSONObject bodyJson = bodyJson(body);
        String pid = firstNonEmpty(bodyJson.optString("pid", ""), url.param("pid", ""), url.param("playlistId", ""));
        String rawIds = firstNonEmpty(bodyJson.optString("id", ""), bodyJson.optString("ids", ""), url.param("id", ""), url.param("ids", ""));
        JSONObject out = new JSONObject();
        if (pid.trim().isEmpty() || rawIds.trim().isEmpty()) {
            out.put("loggedIn", true);
            out.put("success", false);
            out.put("error", "Missing playlist id or song id");
            return out;
        }
        if (hasNeteaseMusicU() && !isCustomPlaylistId(pid)) {
            return neteaseCloudAddSongToPlaylist(pid, rawIds);
        }
        JSONArray added = new JSONArray();
        synchronized (customPlaylists) {
            JSONArray tracks = customPlaylistTracks.get(pid);
            if (tracks == null) {
                tracks = new JSONArray();
                customPlaylistTracks.put(pid, tracks);
            }
            String[] ids = rawIds.split(",");
            for (String idPart : ids) {
                String id = idPart == null ? "" : idPart.trim();
                if (id.isEmpty()) continue;
                JSONObject song = findKnownSong(id);
                if (song == null) song = fallbackNeteaseSong(safeLong(id, 0), "收藏歌曲 " + id, "Mineradio Android", "Android Local");
                if (!containsTrack(tracks, song)) {
                    tracks.put(song);
                    added.put(song);
                }
            }
        }
        persistState();
        out.put("loggedIn", true);
        out.put("androidLocal", true);
        out.put("pid", pid);
        out.put("id", rawIds);
        out.put("success", true);
        out.put("added", added);
        return out;
    }

    private JSONObject qqCloudCreatePlaylist(RequestUrl url, byte[] body) throws Exception {
        JSONObject bodyJson = bodyJson(body);
        String name = firstNonEmpty(bodyJson.optString("name", ""), url.param("name", "")).trim();
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        out.put("androidLocal", false);
        out.put("cloud", true);
        if (name.isEmpty()) {
            out.put("success", false);
            out.put("error", "Missing playlist name");
            return out;
        }
        JSONObject status = qqLoginStatus();
        if (!status.optBoolean("loggedIn", false) || !status.optBoolean("hasCookie", false)) {
            out.put("success", false);
            out.put("error", "QQ_LOGIN_REQUIRED");
            out.put("message", "QQ cloud playlist write requires QQ login.");
            return out;
        }
        String uin = firstNonEmpty(status.optString("uin", ""), status.optString("userId", ""), "0");
        JSONArray attempts = new JSONArray();
        Map<String, String> params = qqPlaylistWriteParams(uin);
        params.put("hostUin", "0");
        params.put("disstName", name);
        params.put("desc", "");
        params.put("tag", "");
        params.put("pic", "");
        JSONObject result = qqWriteGetAttempt(
                "https://c.y.qq.com/splcloud/fcgi-bin/fcg_add_diss.fcg",
                params,
                "https://y.qq.com/n/ryqq/profile",
                "qq_playlist_create",
                attempts);
        boolean success = qqWriteSuccess(result);
        out.put("success", success);
        out.put("loggedIn", true);
        out.put("hasCookie", true);
        out.put("code", qqWriteCode(result));
        out.put("body", result == null ? new JSONObject() : result);
        out.put("attempts", attempts);
        if (!success) {
            out.put("error", firstNonEmpty(qqWriteMessage(result), "QQ_PLAYLIST_CREATE_FAILED"));
            return out;
        }
        JSONObject data = result.optJSONObject("data");
        JSONObject nested = data == null ? null : firstObject(data, new String[]{"dirinfo", "diss_info", "dissInfo", "playlist"});
        String pid = firstNonEmpty(
                result.optString("id", ""),
                result.optString("dirid", ""),
                result.optString("disstid", ""),
                result.optString("dissid", ""),
                result.optString("tid", ""),
                data == null ? "" : data.optString("id", ""),
                data == null ? "" : data.optString("dirid", ""),
                data == null ? "" : data.optString("disstid", ""),
                data == null ? "" : data.optString("dissid", ""),
                data == null ? "" : data.optString("tid", ""),
                nested == null ? "" : nested.optString("id", ""),
                nested == null ? "" : nested.optString("dirid", ""),
                nested == null ? "" : nested.optString("disstid", ""),
                nested == null ? "" : nested.optString("dissid", ""),
                nested == null ? "" : nested.optString("tid", ""));
        JSONObject playlist = new JSONObject();
        playlist.put("provider", "qq");
        playlist.put("source", "qq");
        playlist.put("id", pid);
        playlist.put("name", name);
        playlist.put("creator", "QQ 音乐");
        playlist.put("subscribed", false);
        playlist.put("official", false);
        playlist.put("trackCount", 0);
        playlist.put("playCount", 0);
        playlist.put("specialType", 0);
        playlist.put("cover", "");
        out.put("playlist", playlist);
        return out;
    }

    private JSONObject qqCloudAddSongToPlaylist(RequestUrl url, byte[] body) throws Exception {
        JSONObject bodyJson = bodyJson(body);
        String pid = firstNonEmpty(bodyJson.optString("pid", ""), bodyJson.optString("playlistId", ""), url.param("pid", ""), url.param("playlistId", "")).trim();
        String rawIds = firstNonEmpty(
                bodyJson.optString("mid", ""),
                bodyJson.optString("songmid", ""),
                bodyJson.optString("id", ""),
                bodyJson.optString("qqId", ""),
                bodyJson.optString("ids", ""),
                url.param("mid", ""),
                url.param("songmid", ""),
                url.param("id", ""),
                url.param("qqId", ""),
                url.param("ids", ""));
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        out.put("androidLocal", false);
        out.put("cloud", true);
        out.put("pid", pid);
        out.put("id", rawIds);
        if (pid.isEmpty() || rawIds.trim().isEmpty()) {
            out.put("success", false);
            out.put("error", "Missing playlist id or song id");
            return out;
        }
        JSONObject status = qqLoginStatus();
        if (!status.optBoolean("loggedIn", false) || !status.optBoolean("hasCookie", false)) {
            out.put("success", false);
            out.put("error", "QQ_LOGIN_REQUIRED");
            out.put("message", "QQ cloud playlist write requires QQ login.");
            return out;
        }
        JSONArray mids = jsonStringArray(rawIds);
        if (mids.length() == 0) {
            out.put("success", false);
            out.put("error", "Missing QQ song mid");
            return out;
        }
        String uin = firstNonEmpty(status.optString("uin", ""), status.optString("userId", ""), "0");
        JSONArray attempts = new JSONArray();
        Map<String, String> params = qqPlaylistWriteParams(uin);
        params.put("dirid", pid);
        params.put("midlist", joinStringArray(mids, ","));
        params.put("typelist", repeatValue("13", mids.length(), ","));
        params.put("formsender", "4");
        params.put("source", "153");
        params.put("r2", "0");
        params.put("r3", "1");
        params.put("utf8", "1");
        JSONObject result = qqWriteGetAttempt(
                "https://c.y.qq.com/splcloud/fcgi-bin/fcg_music_add2songdir.fcg",
                params,
                "https://y.qq.com/n/ryqq/playlist/" + pid,
                "qq_playlist_add_midlist",
                attempts);

        if (!qqWriteSuccess(result)) {
            Map<String, String> fallback = qqPlaylistWriteParams(uin);
            fallback.put("dirid", pid);
            fallback.put("songmid", mids.optString(0, ""));
            fallback.put("songid", firstNonEmpty(bodyJson.optString("qqId", ""), bodyJson.optString("songid", ""), url.param("songid", "")));
            fallback.put("songtype", "13");
            fallback.put("formsender", "4");
            fallback.put("source", "153");
            JSONObject second = qqWriteGetAttempt(
                    "https://c.y.qq.com/splcloud/fcgi-bin/fcg_music_add2songdir.fcg",
                    fallback,
                    "https://y.qq.com/n/ryqq/playlist/" + pid,
                    "qq_playlist_add_songmid",
                    attempts);
            if (qqWriteSuccess(second)) result = second;
        }

        boolean success = qqWriteSuccess(result);
        out.put("success", success);
        out.put("loggedIn", true);
        out.put("hasCookie", true);
        out.put("code", qqWriteCode(result));
        out.put("body", result == null ? new JSONObject() : result);
        out.put("attempts", attempts);
        if (!success) out.put("error", firstNonEmpty(qqWriteMessage(result), "QQ_PLAYLIST_ADD_FAILED"));
        return out;
    }

    private Map<String, String> qqPlaylistWriteParams(String uin) {
        Map<String, String> params = queryMap(new String[][]{
                {"g_tk", qqGToken()},
                {"uin", uin},
                {"loginUin", uin},
                {"hostuin", uin},
                {"format", "json"},
                {"inCharset", "utf8"},
                {"outCharset", "utf-8"},
                {"notice", "0"},
                {"platform", "yqq.json"},
                {"needNewCode", "0"}
        });
        return params;
    }

    private JSONObject qqWriteGetAttempt(String target, Map<String, String> params, String referer, String api, JSONArray attempts) throws Exception {
        JSONObject attempt = new JSONObject();
        attempt.put("api", api);
        attempt.put("endpoint", target);
        try {
            JSONObject body = qqGetJson(target, params, referer);
            attempt.put("code", qqWriteCode(body));
            attempt.put("message", qqWriteMessage(body));
            attempt.put("body", body);
            attempts.put(attempt);
            return body;
        } catch (Exception e) {
            attempt.put("code", -1);
            attempt.put("message", e.getMessage());
            attempts.put(attempt);
            JSONObject body = new JSONObject();
            body.put("code", -1);
            body.put("message", e.getMessage());
            return body;
        }
    }

    private boolean qqWriteSuccess(JSONObject body) {
        int code = qqWriteCode(body);
        if (!(code == 0 || code == 200)) return false;
        if (body != null && body.has("subcode") && body.optInt("subcode", 0) != 0) return false;
        JSONObject data = body == null ? null : body.optJSONObject("data");
        return data == null || !data.has("subcode") || data.optInt("subcode", 0) == 0;
    }

    private int qqWriteCode(JSONObject body) {
        if (body == null) return -1;
        if (body.has("code")) return body.optInt("code", -1);
        if (body.has("retcode")) return body.optInt("retcode", -1);
        if (body.has("ret")) return body.optInt("ret", -1);
        if (body.has("subcode")) return body.optInt("subcode", -1);
        JSONObject data = body.optJSONObject("data");
        if (data != null) {
            if (data.has("code")) return data.optInt("code", -1);
            if (data.has("retcode")) return data.optInt("retcode", -1);
            if (data.has("ret")) return data.optInt("ret", -1);
            if (data.has("subcode")) return data.optInt("subcode", -1);
        }
        return -1;
    }

    private String qqWriteMessage(JSONObject body) {
        if (body == null) return "";
        JSONObject data = body.optJSONObject("data");
        return firstNonEmpty(
                body.optString("message", ""),
                body.optString("msg", ""),
                body.optString("errmsg", ""),
                body.optString("errMsg", ""),
                data == null ? "" : data.optString("message", ""),
                data == null ? "" : data.optString("msg", ""),
                data == null ? "" : data.optString("errmsg", ""));
    }

    private JSONObject neteaseCloudCreatePlaylist(String name, String privacy) throws Exception {
        JSONObject data = new JSONObject();
        data.put("name", name);
        data.put("privacy", privacy == null || privacy.trim().isEmpty() ? "0" : privacy.trim());
        data.put("type", "NORMAL");
        HttpResult result = neteaseWeapiPost("/playlist/create", data, true);
        JSONObject root = parseJsonOrJsonp(result.body);
        int code = root.optInt("code", result.statusCode);
        JSONObject rawPlaylist = root.optJSONObject("playlist");
        if (rawPlaylist == null) rawPlaylist = root.optJSONObject("data");
        if (code != 200 || rawPlaylist == null) {
            String message = firstNonEmpty(root.optString("message", ""), root.optString("msg", ""), "NETEASE_PLAYLIST_CREATE_FAILED");
            throw new IOException(message + " (code=" + code + ")");
        }
        JSONObject playlist = mapNeteasePlaylist(rawPlaylist);
        if (playlist == null) playlist = rawPlaylist;
        JSONObject out = new JSONObject();
        out.put("loggedIn", true);
        out.put("androidLocal", false);
        out.put("cloud", true);
        out.put("provider", "netease");
        out.put("code", code);
        out.put("playlist", playlist);
        out.put("body", root);
        return out;
    }

    private JSONObject neteaseCloudAddSongToPlaylist(String pid, String rawIds) throws Exception {
        JSONArray trackIds = jsonStringArray(rawIds);
        JSONArray attempts = new JSONArray();
        JSONObject primaryData = new JSONObject();
        primaryData.put("op", "add");
        primaryData.put("pid", pid);
        primaryData.put("trackIds", trackIds.toString());
        primaryData.put("imme", "true");
        JSONObject primary = neteasePlaylistWriteAttempt("/playlist/manipulate/tracks", primaryData, "playlist_tracks", attempts);
        if (neteasePlaylistWriteSuccess(primary)) return neteasePlaylistWriteResponse(pid, rawIds, true, primary, attempts);

        if (primary.optInt("code", 0) == 512) {
            JSONArray duplicated = new JSONArray();
            for (int i = 0; i < trackIds.length(); i++) duplicated.put(trackIds.optString(i, ""));
            for (int i = 0; i < trackIds.length(); i++) duplicated.put(trackIds.optString(i, ""));
            JSONObject duplicateData = new JSONObject(primaryData.toString());
            duplicateData.put("trackIds", duplicated.toString());
            JSONObject duplicate = neteasePlaylistWriteAttempt("/playlist/manipulate/tracks", duplicateData, "playlist_tracks_duplicate", attempts);
            if (neteasePlaylistWriteSuccess(duplicate)) return neteasePlaylistWriteResponse(pid, rawIds, true, duplicate, attempts);
            primary = duplicate;
        }

        JSONObject fallbackData = new JSONObject();
        fallbackData.put("id", pid);
        JSONArray tracks = new JSONArray();
        for (int i = 0; i < trackIds.length(); i++) {
            tracks.put(new JSONObject().put("type", 3).put("id", trackIds.optString(i, "")));
        }
        fallbackData.put("tracks", tracks.toString());
        JSONObject fallback = neteasePlaylistWriteAttempt("/playlist/track/add", fallbackData, "playlist_track_add", attempts);
        if (neteasePlaylistWriteSuccess(fallback)) return neteasePlaylistWriteResponse(pid, rawIds, true, fallback, attempts);
        return neteasePlaylistWriteResponse(pid, rawIds, false, fallback.optInt("code", 0) == 0 ? primary : fallback, attempts);
    }

    private JSONObject neteasePlaylistWriteAttempt(String path, JSONObject data, String api, JSONArray attempts) throws Exception {
        JSONObject attempt = new JSONObject();
        attempt.put("api", api);
        try {
            HttpResult result = neteaseWeapiPost(path, data, true);
            JSONObject body = parseJsonOrJsonp(result.body);
            int code = body.optInt("code", result.statusCode);
            attempt.put("code", code);
            attempt.put("message", firstNonEmpty(body.optString("message", ""), body.optString("msg", "")));
            attempt.put("body", body);
            attempts.put(attempt);
            return body;
        } catch (Exception e) {
            attempt.put("code", 0);
            attempt.put("message", e.getMessage());
            attempts.put(attempt);
            JSONObject body = new JSONObject();
            body.put("code", 0);
            body.put("message", e.getMessage());
            return body;
        }
    }

    private boolean neteasePlaylistWriteSuccess(JSONObject body) {
        return body != null && body.optInt("code", 0) == 200 && body.optString("error", "").isEmpty();
    }

    private JSONObject neteasePlaylistWriteResponse(String pid, String rawIds, boolean success, JSONObject body, JSONArray attempts) throws Exception {
        JSONObject out = new JSONObject();
        out.put("loggedIn", true);
        out.put("androidLocal", false);
        out.put("cloud", true);
        out.put("provider", "netease");
        out.put("pid", pid);
        out.put("id", rawIds);
        out.put("success", success);
        out.put("code", body == null ? 0 : body.optInt("code", 0));
        out.put("body", body == null ? new JSONObject() : body);
        out.put("attempts", attempts == null ? new JSONArray() : attempts);
        if (!success) {
            out.put("error", firstNonEmpty(body == null ? "" : body.optString("message", ""), body == null ? "" : body.optString("msg", ""), "PLAYLIST_ADD_FAILED"));
        }
        return out;
    }

    private JSONArray jsonStringArray(String rawIds) {
        JSONArray out = new JSONArray();
        if (rawIds == null) return out;
        String[] parts = rawIds.split(",");
        for (String part : parts) {
            String id = part == null ? "" : part.trim();
            if (!id.isEmpty()) out.put(id);
        }
        return out;
    }

    private String joinStringArray(JSONArray values, String separator) {
        if (values == null) return "";
        String sep = separator == null ? "" : separator;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            String value = values.optString(i, "").trim();
            if (value.isEmpty()) continue;
            if (out.length() > 0) out.append(sep);
            out.append(value);
        }
        return out.toString();
    }

    private String repeatValue(String value, int count, String separator) {
        String sep = separator == null ? "" : separator;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (out.length() > 0) out.append(sep);
            out.append(value == null ? "" : value);
        }
        return out.toString();
    }

    private JSONObject likeCheck(RequestUrl url) throws Exception {
        String raw = url.param("ids", url.param("id", ""));
        JSONArray ids = new JSONArray();
        JSONObject liked = new JSONObject();
        synchronized (likedSongState) {
            for (String id : raw.split(",")) {
                String clean = id == null ? "" : id.trim();
                if (clean.isEmpty()) continue;
                ids.put(clean);
                liked.put(clean, Boolean.TRUE.equals(likedSongState.get(clean)));
            }
        }
        JSONObject out = new JSONObject();
        out.put("loggedIn", true);
        out.put("ids", ids);
        out.put("liked", liked);
        return out;
    }

    private JSONObject setSongLike(RequestUrl url, byte[] body) throws Exception {
        JSONObject bodyJson = bodyJson(body);
        String id = firstNonEmpty(bodyJson.optString("id", ""), url.param("id", ""));
        String likeRaw = firstNonEmpty(bodyJson.optString("like", ""), url.param("like", "true"));
        boolean liked = !"false".equalsIgnoreCase(likeRaw) && !"0".equals(likeRaw);
        JSONObject out = new JSONObject();
        if (id.trim().isEmpty()) {
            out.put("loggedIn", true);
            out.put("error", "Missing song id");
            return out;
        }
        synchronized (likedSongState) {
            likedSongState.put(id, liked);
        }
        persistState();
        out.put("loggedIn", true);
        out.put("id", id);
        out.put("liked", liked);
        out.put("code", 200);
        out.put("androidLocal", true);
        return out;
    }

    private JSONObject songComments(RequestUrl url, boolean qq) throws Exception {
        String id = firstNonEmpty(url.param("id", ""), url.param("mid", ""));
        int limit = clampInt(url.param("limit", "18"), 1, 50, 18);
        JSONArray comments = new JSONArray();
        String[] lines = qq
                ? new String[]{"QQ 音乐评论在 Android WebView 中使用本地兼容层展示。", "粒子舞台和歌词视觉优先保持桌面版体验。", "真实账号评论同步需要接入 QQ 登录 Cookie。"}
                : new String[]{"Android 版已保留歌词舞台、粒子视觉和 3D 歌单架。", "当前评论来自本地兼容层，用于保持详情页交互完整。", "导入网易云 Cookie 后可进一步接入真实云端评论。"};
        for (int i = 0; i < lines.length && comments.length() < limit; i++) {
            JSONObject user = new JSONObject();
            user.put("id", "android-comment-" + i);
            user.put("nickname", i == 0 ? "Mineradio Android" : "视觉电台用户");
            user.put("avatar", "");
            JSONObject comment = new JSONObject();
            comment.put("id", (qq ? "qq-" : "ne-") + id + "-" + i);
            comment.put("content", lines[i]);
            comment.put("likedCount", 128 - i * 17);
            comment.put("time", System.currentTimeMillis() - i * 3600L * 1000L);
            comment.put("user", user);
            comments.put(comment);
        }
        JSONObject out = new JSONObject();
        out.put("provider", qq ? "qq" : "netease");
        out.put("id", id);
        out.put("total", comments.length());
        out.put("hot", true);
        out.put("comments", comments);
        return out;
    }

    private JSONObject neteaseArtistDetail(RequestUrl url) throws Exception {
        String id = url.param("id", "");
        int limit = clampInt(url.param("limit", "30"), 1, 80, 30);
        int page = clampInt(url.param("page", url.param("pageNo", "1")), 1, 1000, 1);
        int offset = (page - 1) * limit;
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("provider", "netease");
        out.put("pageNo", page);
        out.put("pageSize", limit);
        out.put("total", 0);
        out.put("more", false);
        JSONArray songs = new JSONArray();
        JSONObject artist = new JSONObject();
        if (!id.trim().isEmpty()) {
            try {
                JSONObject dataReq = new JSONObject();
                dataReq.put("id", id);
                dataReq.put("private_cloud", "true");
                dataReq.put("work_type", 1);
                dataReq.put("order", "hot");
                dataReq.put("offset", offset);
                dataReq.put("limit", limit);
                HttpResult result = neteaseWeapiPost("/v1/artist/songs", dataReq, hasNeteaseMusicU());
                JSONObject root = parseJsonOrJsonp(result.body);
                JSONObject rawArtist = root.optJSONObject("artist");
                if (rawArtist != null) {
                    artist.put("id", rawArtist.optLong("id", safeLong(id, 0)));
                    artist.put("name", rawArtist.optString("name", ""));
                    artist.put("avatar", rawArtist.optString("img1v1Url", rawArtist.optString("picUrl", "")));
                    artist.put("brief", rawArtist.optString("briefDesc", ""));
                    artist.put("musicSize", rawArtist.optInt("musicSize", 0));
                    artist.put("albumSize", rawArtist.optInt("albumSize", 0));
                }
                JSONArray rawSongs = root.optJSONArray("songs");
                if (rawSongs == null) {
                    JSONObject data = root.optJSONObject("data");
                    rawSongs = data == null ? null : data.optJSONArray("songs");
                    if (rawArtist == null && data != null) {
                        rawArtist = data.optJSONObject("artist");
                        if (rawArtist != null) {
                            artist.put("id", rawArtist.optLong("id", safeLong(id, 0)));
                            artist.put("name", rawArtist.optString("name", ""));
                            artist.put("avatar", rawArtist.optString("img1v1Url", rawArtist.optString("picUrl", "")));
                            artist.put("brief", rawArtist.optString("briefDesc", ""));
                            artist.put("musicSize", rawArtist.optInt("musicSize", 0));
                            artist.put("albumSize", rawArtist.optInt("albumSize", 0));
                        }
                    }
                }
                if (rawSongs != null) {
                    for (int i = 0; i < rawSongs.length() && songs.length() < limit; i++) {
                        songs.put(mapNeteaseSong(rawSongs.optJSONObject(i)));
                    }
                }
                int total = firstPositiveInt(root, new String[]{"total", "songCount"});
                JSONObject data = root.optJSONObject("data");
                if (total <= 0 && data != null) total = firstPositiveInt(data, new String[]{"total", "songCount"});
                if (total <= 0) total = artist.optInt("musicSize", songs.length());
                out.put("total", total);
                out.put("more", offset + songs.length() < total);
                out.put("source", "artist_songs");
            } catch (Exception e) {
                Log.w(TAG, "artist paged songs failed: " + e.getMessage());
            }
            if (songs.length() == 0 && page == 1) {
                try {
                    String json = httpGet("https://music.163.com/api/artist/" + encode(id), null);
                    JSONObject root = new JSONObject(json);
                    JSONObject rawArtist = root.optJSONObject("artist");
                    if (rawArtist != null) {
                        artist.put("id", rawArtist.optLong("id", safeLong(id, 0)));
                        artist.put("name", rawArtist.optString("name", ""));
                        artist.put("avatar", rawArtist.optString("img1v1Url", rawArtist.optString("picUrl", "")));
                        artist.put("brief", rawArtist.optString("briefDesc", ""));
                        artist.put("musicSize", rawArtist.optInt("musicSize", 0));
                        artist.put("albumSize", rawArtist.optInt("albumSize", 0));
                    }
                    JSONArray hot = root.optJSONArray("hotSongs");
                    if (hot != null) {
                        for (int i = 0; i < hot.length() && songs.length() < limit; i++) {
                            songs.put(mapNeteaseSong(hot.optJSONObject(i)));
                        }
                    }
                    int total = artist.optInt("musicSize", songs.length());
                    out.put("total", total);
                    out.put("more", songs.length() < total);
                    out.put("source", "artist_hot_fallback");
                } catch (Exception e) {
                    Log.w(TAG, "artist detail failed: " + e.getMessage());
                }
            }
            if (artist.length() == 0) {
                try {
                    String json = httpGet("https://music.163.com/api/artist/" + encode(id), null);
                    JSONObject root = new JSONObject(json);
                    JSONObject rawArtist = root.optJSONObject("artist");
                    if (rawArtist != null) {
                        artist.put("id", rawArtist.optLong("id", safeLong(id, 0)));
                        artist.put("name", rawArtist.optString("name", ""));
                        artist.put("avatar", rawArtist.optString("img1v1Url", rawArtist.optString("picUrl", "")));
                        artist.put("brief", rawArtist.optString("briefDesc", ""));
                        artist.put("musicSize", rawArtist.optInt("musicSize", 0));
                        artist.put("albumSize", rawArtist.optInt("albumSize", 0));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        out.put("artist", artist);
        out.put("songs", songs);
        return out;
    }

    private JSONObject qqArtistDetail(RequestUrl url) throws Exception {
        String mid = firstNonEmpty(url.param("mid", ""), url.param("id", ""));
        JSONObject out = new JSONObject();
        out.put("provider", "qq");
        if (mid.trim().isEmpty()) {
            out.put("error", "MISSING_SINGER_MID");
            out.put("artist", JSONObject.NULL);
            out.put("songs", new JSONArray());
            return out;
        }
        int limit = clampInt(url.param("limit", "36"), 10, 80, 36);
        int page = clampInt(url.param("page", url.param("pageNo", "1")), 1, 1000, 1);
        int offset = (page - 1) * limit;
        out.put("pageNo", page);
        out.put("pageSize", limit);
        out.put("total", 0);
        out.put("more", false);
        JSONObject payload = new JSONObject();
        payload.put("comm", new JSONObject().put("ct", 24).put("cv", 0));
        payload.put("singer", new JSONObject()
                .put("module", "music.web_singer_info_svr")
                .put("method", "get_singer_detail_info")
                .put("param", new JSONObject()
                        .put("sort", 5)
                        .put("singermid", mid)
                        .put("sin", offset)
                        .put("num", limit)));
        String json = httpPost("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json", payload.toString(), "application/json; charset=UTF-8", qqHeaders());
        JSONObject root = new JSONObject(json);
        JSONObject block = root.optJSONObject("singer");
        if (block == null || block.optInt("code", 0) != 0) {
            out.put("error", block == null ? "QQ_ARTIST_DETAIL_FAILED" : firstNonEmpty(block.optString("message", ""), block.optString("msg", ""), String.valueOf(block.optInt("code", -1))));
            out.put("artist", JSONObject.NULL);
            out.put("songs", new JSONArray());
            out.put("body", root);
            return out;
        }
        JSONObject data = block.optJSONObject("data");
        if (data == null) data = new JSONObject();
        JSONObject info = firstObject(data, new String[]{"singer_info", "singerInfo"});
        if (info == null) info = new JSONObject();
        JSONArray rawSongs = data.optJSONArray("songlist");
        JSONArray songs = new JSONArray();
        if (rawSongs != null) {
            for (int i = 0; i < rawSongs.length() && songs.length() < limit; i++) {
                JSONObject song = mapQQPlaylistTrack(rawSongs.optJSONObject(i));
                if (song != null && !song.optString("name", "").isEmpty() &&
                        !firstNonEmpty(song.optString("mid", ""), song.optString("id", "")).isEmpty()) {
                    songs.put(song);
                }
            }
        }
        JSONObject matchedArtist = null;
        if (songs.length() > 0) {
            JSONArray artists = songs.optJSONObject(0) == null ? null : songs.optJSONObject(0).optJSONArray("artists");
            if (artists != null) {
                for (int i = 0; i < artists.length(); i++) {
                    JSONObject candidate = artists.optJSONObject(i);
                    if (candidate != null && mid.equals(candidate.optString("mid", ""))) {
                        matchedArtist = candidate;
                        break;
                    }
                }
            }
        }
        String artistMid = firstNonEmpty(info.optString("mid", ""), mid);
        String artistName = firstNonEmpty(info.optString("name", ""), info.optString("title", ""), matchedArtist == null ? "" : matchedArtist.optString("name", ""));
        int totalSong = firstPositiveInt(data, new String[]{"total_song", "song_count"});
        if (totalSong <= 0) totalSong = songs.length();
        JSONObject artist = new JSONObject();
        artist.put("provider", "qq");
        artist.put("id", info.optString("id", ""));
        artist.put("mid", artistMid);
        artist.put("name", artistName);
        artist.put("avatar", firstNonEmpty(info.optString("pic", ""), info.optString("avatar", ""), qqSingerAvatar(artistMid, 300)));
        artist.put("fans", firstPositiveLong(info, new String[]{"fans"}));
        artist.put("musicSize", totalSong);
        artist.put("albumSize", firstPositiveInt(data, new String[]{"total_album"}));
        artist.put("mvSize", firstPositiveInt(data, new String[]{"total_mv"}));
        out.put("artist", artist);
        out.put("total", totalSong);
        out.put("more", offset + songs.length() < totalSong);
        out.put("songs", songs);
        return out;
    }

    private JSONObject podcastHot(RequestUrl url) throws Exception {
        JSONArray radios = starterPodcastRadios();
        int limit = clampInt(url.param("limit", "18"), 1, 30, 18);
        JSONObject out = new JSONObject();
        out.put("podcasts", sliceArray(radios, limit));
        out.put("more", false);
        out.put("androidLocal", true);
        return out;
    }

    private JSONObject podcastSearch(RequestUrl url) throws Exception {
        String keywords = url.param("keywords", "").toLowerCase(Locale.US).trim();
        int limit = clampInt(url.param("limit", "18"), 1, 30, 18);
        JSONArray radios = starterPodcastRadios();
        JSONArray matched = new JSONArray();
        for (int i = 0; i < radios.length() && matched.length() < limit; i++) {
            JSONObject radio = radios.optJSONObject(i);
            if (radio == null) continue;
            String haystack = (radio.optString("name", "") + " " + radio.optString("djName", "") + " "
                    + radio.optString("category", "") + " " + radio.optString("desc", "")).toLowerCase(Locale.US);
            if (keywords.isEmpty() || haystack.contains(keywords)) matched.put(radio);
        }
        if (!keywords.isEmpty() && matched.length() == 0) matched = sliceArray(radios, limit);
        JSONObject out = new JSONObject();
        out.put("podcasts", matched);
        out.put("total", matched.length());
        out.put("androidLocal", true);
        return out;
    }

    private JSONObject podcastDetail(RequestUrl url) throws Exception {
        JSONObject out = new JSONObject();
        out.put("podcast", podcastRadioById(firstNonEmpty(url.param("id", ""), url.param("rid", ""))));
        out.put("androidLocal", true);
        return out;
    }

    private JSONObject podcastPrograms(RequestUrl url) throws Exception {
        String rid = firstNonEmpty(url.param("id", ""), url.param("rid", ""));
        int limit = clampInt(url.param("limit", "30"), 1, 60, 30);
        JSONObject radio = podcastRadioById(rid);
        JSONArray programs = starterPodcastPrograms(radio.optString("id", rid), limit);
        JSONObject out = new JSONObject();
        out.put("radio", radio);
        out.put("programs", programs);
        out.put("more", false);
        out.put("total", programs.length());
        out.put("androidLocal", true);
        return out;
    }

    private JSONObject podcastMyItems(RequestUrl url) throws Exception {
        String key = url.param("key", "android-weather");
        JSONArray items;
        String itemType;
        if ("liked".equals(key) || "android-liked".equals(key)) {
            items = starterPodcastPrograms("android-weather", clampInt(url.param("limit", "36"), 1, 60, 36));
            itemType = "voice";
        } else {
            items = starterPodcastRadios();
            itemType = "radio";
        }
        JSONObject out = new JSONObject();
        out.put("loggedIn", true);
        out.put("key", key);
        out.put("title", "天气电台预设");
        out.put("sub", "Android starter podcasts");
        out.put("count", items.length());
        out.put("cover", items.length() == 0 ? "" : items.optJSONObject(0).optString("cover", ""));
        out.put("itemType", itemType);
        out.put("items", items);
        out.put("androidLocal", true);
        return out;
    }

    private JSONObject podcastDjBeatmap() throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", false);
        out.put("error", "Android WebView uses the in-page OfflineAudioContext DJ beat analyzer.");
        out.put("androidLocal", true);
        return out;
    }

    private JSONArray starterPodcastRadios() throws Exception {
        JSONArray out = new JSONArray();
        out.put(starterPodcastRadio("android-weather", "天气视觉电台", "Mineradio Android", "天气数据触发主题与音频效果", "Weather", 6, 54200));
        out.put(starterPodcastRadio("android-night", "夜航粒子播客", "Mineradio Android", "长音频 DJ 锁拍与粒子背景", "Visual", 6, 43800));
        out.put(starterPodcastRadio("android-city", "城市巡航声音", "Mineradio Android", "适合 3D 歌单架测试的声音卡片", "City", 6, 39100));
        return out;
    }

    private JSONObject starterPodcastRadio(String id, String name, String djName, String desc, String category, int programCount, int subCount) throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("rid", id);
        out.put("radioId", id);
        out.put("type", "podcast-radio");
        out.put("sourceType", "podcast-radio");
        out.put("source", "podcast");
        out.put("name", name);
        out.put("artist", djName);
        out.put("album", category);
        out.put("cover", "");
        out.put("desc", desc);
        out.put("djName", djName);
        out.put("category", category);
        out.put("programCount", programCount);
        out.put("subCount", subCount);
        return out;
    }

    private JSONObject podcastRadioById(String id) throws Exception {
        JSONArray radios = starterPodcastRadios();
        for (int i = 0; i < radios.length(); i++) {
            JSONObject radio = radios.optJSONObject(i);
            if (radio != null && (id == null || id.isEmpty() || id.equals(radio.optString("id", "")))) {
                return new JSONObject(radio.toString());
            }
        }
        return new JSONObject(radios.optJSONObject(0).toString());
    }

    private JSONArray starterPodcastPrograms(String rid, int limit) throws Exception {
        JSONObject radio = podcastRadioById(rid);
        JSONArray base = fallbackStarterTracks(false);
        JSONArray out = new JSONArray();
        for (int i = 0; i < base.length() && out.length() < limit; i++) {
            JSONObject song = new JSONObject(base.optJSONObject(i).toString());
            String radioName = radio.optString("name", "Podcast");
            song.put("type", "podcast");
            song.put("source", "podcast");
            song.put("sourceType", "podcast-voice");
            song.put("programId", radio.optString("id", "android-podcast") + "-program-" + (i + 1));
            song.put("radioId", radio.optString("id", ""));
            song.put("radioName", radioName);
            song.put("djName", radio.optString("djName", ""));
            song.put("album", radioName);
            song.put("artist", radioName);
            song.put("duration", song.optLong("duration", 0) > 0 ? song.optLong("duration") : (180000L + i * 24000L));
            song.put("desc", radio.optString("desc", ""));
            out.put(song);
        }
        return out;
    }

    private JSONObject bodyJson(byte[] body) {
        if (body == null || body.length == 0) return new JSONObject();
        try {
            String text = new String(body, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject beatmapCacheStatus() throws Exception {
        File dir = beatmapCacheDir();
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("enabled", true);
        out.put("mode", "disk");
        out.put("count", beatmapCacheFiles(dir).size());
        out.put("maxEntries", BEATMAP_CACHE_MAX_ENTRIES);
        return out;
    }

    private JSONObject beatmapCacheGet(RequestUrl url) throws Exception {
        String key = url.param("key", "");
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("enabled", true);
        out.put("mode", "disk");
        out.put("key", key);
        if (key.trim().isEmpty()) {
            out.put("hit", false);
            out.put("found", false);
            out.put("reason", "MISSING_KEY");
            return out;
        }
        File file = beatmapCacheFile(key);
        if (!file.isFile()) {
            out.put("hit", false);
            out.put("found", false);
            return out;
        }
        JSONObject entry = readJsonFile(file);
        JSONObject map = entry.optJSONObject("map");
        if (map == null) {
            file.delete();
            out.put("hit", false);
            out.put("found", false);
            out.put("reason", "INVALID_ENTRY");
            return out;
        }
        out.put("hit", true);
        out.put("found", true);
        out.put("beatMode", entry.optString("mode", ""));
        out.put("provider", entry.optString("provider", ""));
        out.put("title", entry.optString("title", ""));
        out.put("artist", entry.optString("artist", ""));
        out.put("updatedAt", entry.optLong("updatedAt", 0));
        out.put("map", map);
        return out;
    }

    private JSONObject beatmapCachePut(byte[] body) throws Exception {
        JSONObject input = bodyJson(body);
        String key = input.optString("key", "").trim();
        JSONObject map = input.optJSONObject("map");
        JSONObject out = new JSONObject();
        out.put("enabled", true);
        out.put("mode", "disk");
        out.put("key", key);
        if (key.isEmpty() || map == null) {
            out.put("ok", false);
            out.put("saved", false);
            out.put("reason", key.isEmpty() ? "MISSING_KEY" : "MISSING_MAP");
            return out;
        }
        JSONObject entry = new JSONObject();
        entry.put("key", key);
        entry.put("mode", input.optString("mode", "mr"));
        entry.put("provider", input.optString("provider", ""));
        entry.put("title", input.optString("title", ""));
        entry.put("artist", input.optString("artist", ""));
        entry.put("updatedAt", System.currentTimeMillis());
        entry.put("map", new JSONObject(map.toString()));
        writeJsonFile(beatmapCacheFile(key), entry);
        pruneBeatmapCache();
        out.put("ok", true);
        out.put("saved", true);
        out.put("beatMode", entry.optString("mode", ""));
        out.put("count", beatmapCacheFiles(beatmapCacheDir()).size());
        return out;
    }

    private File beatmapCacheDir() throws IOException {
        File dir = new File(context.getFilesDir(), "beatmap-cache");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create beatmap cache dir");
        }
        return dir;
    }

    private File beatmapCacheFile(String key) throws Exception {
        return new File(beatmapCacheDir(), sha256Hex(key) + ".json");
    }

    private List<File> beatmapCacheFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return out;
        for (File file : files) {
            if (file != null && file.isFile() && file.getName().endsWith(".json")) out.add(file);
        }
        return out;
    }

    private void pruneBeatmapCache() throws IOException {
        List<File> files = beatmapCacheFiles(beatmapCacheDir());
        while (files.size() > BEATMAP_CACHE_MAX_ENTRIES) {
            int oldestIndex = 0;
            long oldest = files.get(0).lastModified();
            for (int i = 1; i < files.size(); i++) {
                long modified = files.get(i).lastModified();
                if (modified < oldest) {
                    oldest = modified;
                    oldestIndex = i;
                }
            }
            File victim = files.remove(oldestIndex);
            if (victim != null) victim.delete();
        }
    }

    private JSONObject readJsonFile(File file) throws Exception {
        return new JSONObject(new String(readAll(new FileInputStream(file)), StandardCharsets.UTF_8));
    }

    private void writeJsonFile(File file, JSONObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create cache dir");
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(tmp);
            output.write(bytes);
            output.flush();
        } finally {
            closeQuietly(output);
        }
        if (file.exists() && !file.delete()) {
            tmp.delete();
            throw new IOException("Cannot replace cache file");
        }
        if (!tmp.renameTo(file)) {
            output = null;
            try {
                output = new FileOutputStream(file);
                output.write(bytes);
                output.flush();
            } finally {
                closeQuietly(output);
                tmp.delete();
            }
        }
    }

    private static String sha256Hex(String text) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xff);
            if (hex.length() == 1) out.append('0');
            out.append(hex);
        }
        return out.toString();
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return "";
    }

    private String joinNonEmpty(String separator, String... values) {
        StringBuilder out = new StringBuilder();
        if (values == null) return "";
        String sep = separator == null ? "" : separator;
        for (String value : values) {
            String clean = value == null ? "" : value.trim();
            if (clean.isEmpty()) continue;
            if (out.length() > 0) out.append(sep);
            out.append(clean);
        }
        return out.toString();
    }

    private boolean isCustomPlaylistId(String id) {
        return id != null && id.startsWith("android-custom-");
    }

    private JSONObject findCustomPlaylist(String id) throws Exception {
        synchronized (customPlaylists) {
            for (int i = 0; i < customPlaylists.size(); i++) {
                JSONObject playlist = customPlaylists.get(i);
                if (playlist != null && id != null && id.equals(playlist.optString("id", ""))) {
                    return new JSONObject(playlist.toString());
                }
            }
        }
        JSONObject playlist = new JSONObject();
        playlist.put("id", id == null ? "" : id);
        playlist.put("name", "Android 本地歌单");
        playlist.put("creator", "Mineradio Android");
        playlist.put("subscribed", false);
        playlist.put("trackCount", 0);
        playlist.put("playCount", 0);
        playlist.put("provider", "netease");
        playlist.put("source", "netease");
        playlist.put("cover", "");
        return playlist;
    }

    private JSONObject customPlaylistMeta(JSONObject stored) throws Exception {
        JSONObject playlist = stored == null ? findCustomPlaylist("") : new JSONObject(stored.toString());
        String id = playlist.optString("id", "");
        JSONArray tracks = customPlaylistTracks.get(id);
        playlist.put("trackCount", tracks == null ? 0 : tracks.length());
        if (playlist.optString("cover", "").isEmpty() && tracks != null && tracks.length() > 0) {
            JSONObject first = tracks.optJSONObject(0);
            if (first != null) playlist.put("cover", first.optString("cover", ""));
        }
        return playlist;
    }

    private JSONObject findKnownSong(String id) throws Exception {
        if (id == null || id.trim().isEmpty()) return null;
        synchronized (customPlaylists) {
            for (JSONArray tracks : customPlaylistTracks.values()) {
                JSONObject found = findTrackInArray(tracks, id);
                if (found != null) return found;
            }
        }
        synchronized (starterTrackCache) {
            for (JSONArray tracks : starterTrackCache.values()) {
                JSONObject found = findTrackInArray(tracks, id);
                if (found != null) return found;
            }
        }
        JSONObject found = findTrackInArray(fallbackStarterTracks(false), id);
        if (found != null) return found;
        long numeric = safeLong(id, 0);
        return numeric > 0 ? fallbackNeteaseSong(numeric, "收藏歌曲 " + id, "Mineradio Android", "Android Local") : null;
    }

    private JSONObject findTrackInArray(JSONArray tracks, String id) throws Exception {
        if (tracks == null) return null;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject song = tracks.optJSONObject(i);
            if (song == null) continue;
            if (id.equals(song.optString("id", "")) || id.equals(song.optString("mid", "")) || id.equals(song.optString("songmid", ""))) {
                return new JSONObject(song.toString());
            }
        }
        return null;
    }

    private JSONArray sliceArray(JSONArray input, int limit) throws Exception {
        JSONArray out = new JSONArray();
        if (input == null) return out;
        for (int i = 0; i < input.length() && out.length() < limit; i++) {
            Object value = input.opt(i);
            if (value instanceof JSONObject) out.put(new JSONObject(value.toString()));
            else if (value instanceof JSONArray) out.put(new JSONArray(value.toString()));
            else out.put(value);
        }
        return out;
    }

    private JSONObject loginEmpty(String path) throws Exception {
        JSONObject out = new JSONObject();
        if (path.contains("/qq/")) out.put("provider", "qq");
        out.put("loggedIn", false);
        out.put("hasCookie", false);
        out.put("playbackKeyReady", false);
        out.put("message", "Android build does not import desktop cookies.");
        if (path.endsWith("/key")) out.put("key", "");
        if (path.endsWith("/create")) {
            out.put("img", "");
            out.put("url", "");
        }
        if (path.endsWith("/check")) out.put("code", 800);
        return out;
    }

    private JSONObject emptyDomainResponse(String path, RequestUrl url) throws Exception {
        JSONObject out = new JSONObject();
        out.put("loggedIn", false);
        out.put("provider", path.contains("/qq/") ? "qq" : "netease");
        if (path.contains("comments")) {
            out.put("comments", new JSONArray());
            out.put("total", 0);
        } else if (path.contains("like/check")) {
            JSONArray ids = new JSONArray();
            JSONObject liked = new JSONObject();
            String raw = url.param("ids", url.param("id", ""));
            for (String id : raw.split(",")) {
                String clean = id.trim();
                if (!clean.isEmpty()) {
                    ids.put(clean);
                    liked.put(clean, false);
                }
            }
            out.put("ids", ids);
            out.put("liked", liked);
        } else if (path.contains("playlist/tracks")) {
            out.put("tracks", new JSONArray());
            JSONObject playlist = new JSONObject();
            playlist.put("id", url.param("id", ""));
            playlist.put("name", "");
            playlist.put("cover", "");
            playlist.put("trackCount", 0);
            out.put("playlist", playlist);
        } else if (path.contains("playlist") || path.contains("user/playlists")) {
            out.put("playlists", new JSONArray());
        } else if (path.contains("podcast")) {
            out.put("podcasts", new JSONArray());
            out.put("programs", new JSONArray());
            out.put("collections", new JSONArray());
        } else if (path.contains("artist/detail")) {
            out.put("songs", new JSONArray());
            out.put("artist", new JSONObject());
        }
        return out;
    }

    private JSONObject searchNeteaseResult(String keywords, int limit, int page) {
        JSONObject out = new JSONObject();
        JSONArray mapped = new JSONArray();
        int pageNo = Math.max(1, page);
        int pageSize = Math.max(1, limit);
        try {
            out.put("provider", "netease");
            out.put("songs", mapped);
            out.put("total", 0);
            out.put("pageNo", pageNo);
            out.put("pageSize", pageSize);
            out.put("more", false);
            out.put("source", "empty");
        } catch (Exception ignored) {
        }
        if (keywords == null || keywords.trim().isEmpty()) return out;
        try {
            int offset = (pageNo - 1) * pageSize;
            String form = "s=" + encode(keywords) + "&type=1&offset=" + offset + "&limit=" + pageSize;
            String json = httpPost("https://music.163.com/api/search/get/web", form, "application/x-www-form-urlencoded; charset=UTF-8", null);
            JSONObject root = new JSONObject(json);
            JSONObject result = root.optJSONObject("result");
            JSONArray songs = result == null ? null : result.optJSONArray("songs");
            if (songs == null) return out;
            for (int i = 0; i < songs.length() && mapped.length() < pageSize; i++) {
                mapped.put(mapNeteaseSong(songs.optJSONObject(i)));
            }
            int total = result == null ? mapped.length() : result.optInt("songCount", result.optInt("total", mapped.length()));
            out.put("songs", mapped);
            out.put("total", total);
            out.put("more", offset + mapped.length() < total);
            out.put("source", "netease_search");
        } catch (Exception e) {
            Log.w(TAG, "netease search failed: " + e.getMessage());
            try {
                out.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private JSONArray searchNetease(String keywords, int limit) {
        JSONArray songs = searchNeteaseResult(keywords, limit, 1).optJSONArray("songs");
        return songs == null ? new JSONArray() : songs;
    }

    private JSONObject mapNeteaseSong(JSONObject song) throws Exception {
        JSONObject out = new JSONObject();
        if (song == null) song = new JSONObject();
        JSONArray rawArtists = song.optJSONArray("artists");
        if (rawArtists == null) rawArtists = song.optJSONArray("ar");
        JSONArray artists = new JSONArray();
        List<String> names = new ArrayList<>();
        long artistId = 0;
        if (rawArtists != null) {
            for (int i = 0; i < rawArtists.length(); i++) {
                JSONObject raw = rawArtists.optJSONObject(i);
                if (raw == null) continue;
                JSONObject artist = new JSONObject();
                artist.put("id", raw.optLong("id"));
                artist.put("name", raw.optString("name"));
                if (artistId == 0) artistId = raw.optLong("id");
                if (!raw.optString("name").isEmpty()) names.add(raw.optString("name"));
                artists.put(artist);
            }
        }
        JSONObject album = song.optJSONObject("album");
        if (album == null) album = song.optJSONObject("al");
        if (album == null) album = new JSONObject();
        out.put("provider", "netease");
        out.put("source", "netease");
        out.put("type", "song");
        out.put("id", song.optLong("id"));
        out.put("name", song.optString("name"));
        out.put("artist", join(names, " / "));
        out.put("artists", artists);
        out.put("artistId", artistId);
        out.put("album", album.optString("name"));
        out.put("cover", album.optString("picUrl", album.optString("coverUrl", "")));
        out.put("duration", song.optLong("duration", song.optLong("dt", 0)));
        out.put("fee", song.optInt("fee", 0));
        return out;
    }

    private JSONObject neteaseLyric(String id) {
        JSONObject out = new JSONObject();
        try {
            if (id == null || id.trim().isEmpty()) {
                out.put("lyric", "");
                return out;
            }
            String json = httpGet("https://music.163.com/api/song/lyric?id=" + encode(id) + "&lv=-1&kv=-1&tv=-1", null);
            JSONObject root = new JSONObject(json);
            JSONObject lrc = root.optJSONObject("lrc");
            JSONObject tlyric = root.optJSONObject("tlyric");
            out.put("lyric", lrc == null ? "" : lrc.optString("lyric", ""));
            out.put("tlyric", tlyric == null ? "" : tlyric.optString("lyric", ""));
            out.put("yrc", "");
            out.put("source", "android");
        } catch (Exception e) {
            try {
                out.put("lyric", "");
                out.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private String normalizeLyricProviderMode(String value) {
        return "third".equalsIgnoreCase(value == null ? "" : value.trim()) ? "third" : "original";
    }

    private String extractXmlTagText(String xml, String tag) {
        String raw = xml == null ? "" : xml;
        java.util.regex.Pattern cdataPattern = java.util.regex.Pattern.compile("<" + tag + "\\b[^>]*>\\s*<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>\\s*</" + tag + ">", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher cdata = cdataPattern.matcher(raw);
        if (cdata.find()) return cdata.group(1) == null ? "" : cdata.group(1);
        java.util.regex.Pattern plainPattern = java.util.regex.Pattern.compile("<" + tag + "\\b[^>]*>([\\s\\S]*?)</" + tag + ">", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher plain = plainPattern.matcher(raw);
        return plain.find() ? decodeHtmlEntities(plain.group(1) == null ? "" : plain.group(1)) : "";
    }

    private String comparableLyricMeta(String text) {
        return (text == null ? "" : text)
                .toLowerCase(Locale.US)
                .replaceAll("[（(].*?[)）]", "")
                .replaceAll("[\\s\\-_/\\\\|·.,，。:：;；!！?？'\"“”‘’《》<>【】\\[\\]()（）]", "");
    }

    private int scoreQQThirdPartyLyricCandidate(JSONObject song, String name, String artist, String album) {
        if (song == null) return -1;
        String targetName = comparableLyricMeta(name);
        String targetArtist = comparableLyricMeta(artist);
        String targetAlbum = comparableLyricMeta(album);
        String songName = comparableLyricMeta(song.optString("name", ""));
        String songArtist = comparableLyricMeta(song.optString("artist", ""));
        String songAlbum = comparableLyricMeta(song.optString("album", ""));
        int score = normalizeQQSongId(firstNonEmpty(song.optString("qqId", ""), song.optString("id", ""))).isEmpty() ? 0 : 8;
        if (!targetName.isEmpty() && songName.equals(targetName)) score += 80;
        else if (!targetName.isEmpty() && !songName.isEmpty() && (songName.contains(targetName) || targetName.contains(songName))) score += 42;
        if (!targetArtist.isEmpty() && songArtist.equals(targetArtist)) score += 44;
        else if (!targetArtist.isEmpty() && !songArtist.isEmpty() && (songArtist.contains(targetArtist) || targetArtist.contains(songArtist))) score += 24;
        if (!targetAlbum.isEmpty() && songAlbum.equals(targetAlbum)) score += 14;
        return score;
    }

    private JSONObject searchQQThirdPartyLyricSong(String name, String artist, String album) throws Exception {
        String query = joinNonEmpty(" ", name, artist, album);
        if (query.isEmpty()) return null;
        JSONObject payload = new JSONObject();
        payload.put("comm", new JSONObject().put("ct", "19").put("cv", "1859").put("uin", "0"));
        payload.put("req", new JSONObject()
                .put("method", "DoSearchForQQMusicDesktop")
                .put("module", "music.search.SearchCgiService")
                .put("param", new JSONObject()
                        .put("grp", 1)
                        .put("num_per_page", 6)
                        .put("page_num", 1)
                        .put("query", query)
                        .put("search_type", 0)));
        Map<String, String> headers = qqHeaders();
        headers.remove("Cookie");
        String json = httpPost("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json", payload.toString(), "application/json; charset=UTF-8", headers);
        JSONObject root = new JSONObject(json);
        JSONObject req = root.optJSONObject("req");
        JSONObject data = req == null ? null : req.optJSONObject("data");
        JSONObject body = data == null ? null : data.optJSONObject("body");
        JSONObject songNode = body == null ? null : body.optJSONObject("song");
        JSONArray list = songNode == null ? null : songNode.optJSONArray("list");
        if (list == null || list.length() == 0) return null;
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < list.length(); i++) {
            JSONObject mapped = mapQQPlaylistTrack(list.optJSONObject(i));
            if (mapped == null) continue;
            if (normalizeQQSongId(firstNonEmpty(mapped.optString("qqId", ""), mapped.optString("id", ""))).isEmpty()) continue;
            int score = scoreQQThirdPartyLyricCandidate(mapped, name, artist, album);
            if (score > bestScore) {
                bestScore = score;
                best = mapped;
            }
        }
        return best;
    }

    private JSONObject downloadQQThirdPartyQrc(String songId) throws Exception {
        String musicId = normalizeQQSongId(songId);
        if (musicId.isEmpty()) return null;
        String body = "version=15&miniversion=100&lrctype=4&musicid=" + encode(musicId);
        Map<String, String> headers = qqHeaders();
        headers.put("Referer", "https://y.qq.com/");
        String raw = httpPost(QQ_QRC_DOWNLOAD_URL, body, "application/x-www-form-urlencoded", headers);
        JSONObject out = new JSONObject();
        out.put("qrc", "");
        out.put("qrcEncrypted", extractXmlTagText(raw, "content"));
        out.put("tlyric", "");
        out.put("tlyricEncrypted", extractXmlTagText(raw, "contentts"));
        out.put("roma", "");
        out.put("romaEncrypted", extractXmlTagText(raw, "contentroma"));
        return out;
    }

    private JSONObject qqThirdPartyLyric(String mid, String id, String name, String artist, String album) throws Exception {
        JSONObject out = new JSONObject();
        String songMid = mid == null ? "" : mid.trim();
        String songId = normalizeQQSongId(id);
        JSONObject matched = null;
        String source = "qq-third-qrc";
        if (songId.isEmpty()) {
            matched = searchQQThirdPartyLyricSong(name, artist, album);
            songId = normalizeQQSongId(matched == null ? "" : firstNonEmpty(matched.optString("qqId", ""), matched.optString("id", "")));
            source = "qq-third-search-qrc";
        }
        out.put("provider", "qq");
        out.put("lyricProvider", "third");
        out.put("id", songId);
        out.put("mid", firstNonEmpty(songMid, matched == null ? "" : matched.optString("mid", "")));
        out.put("lyric", "");
        out.put("tlyric", "");
        out.put("yrc", "");
        out.put("qrc", "");
        out.put("roma", "");
        if (songId.isEmpty()) {
            out.put("source", "qq-third-empty");
            out.put("error", "Missing QQ numeric song id");
            return out;
        }
        JSONObject downloaded = downloadQQThirdPartyQrc(songId);
        String qrc = downloaded == null ? "" : downloaded.optString("qrc", "");
        String qrcEncrypted = downloaded == null ? "" : downloaded.optString("qrcEncrypted", "");
        out.put("qrc", qrc);
        out.put("qrcEncrypted", qrcEncrypted);
        out.put("tlyric", downloaded == null ? "" : downloaded.optString("tlyric", ""));
        out.put("tlyricEncrypted", downloaded == null ? "" : downloaded.optString("tlyricEncrypted", ""));
        out.put("roma", downloaded == null ? "" : downloaded.optString("roma", ""));
        out.put("romaEncrypted", downloaded == null ? "" : downloaded.optString("romaEncrypted", ""));
        out.put("source", qrc.isEmpty() && qrcEncrypted.isEmpty() ? "qq-third-empty" : source);
        if (matched != null) {
            out.put("matched", new JSONObject()
                    .put("id", firstNonEmpty(matched.optString("qqId", ""), matched.optString("id", "")))
                    .put("mid", matched.optString("mid", ""))
                    .put("name", matched.optString("name", ""))
                    .put("artist", matched.optString("artist", ""))
                    .put("album", matched.optString("album", "")));
        }
        return out;
    }

    private JSONObject qqLyric(String mid, String id, String lyricProvider, String name, String artist, String album) {
        JSONObject out = new JSONObject();
        String songMid = mid == null ? "" : mid.trim();
        String songId = normalizeQQSongId(id);
        String lyricText = "";
        String transText = "";
        String qrcText = "";
        String romaText = "";
        String source = "qq-musicu";
        try {
            out.put("provider", "qq");
            if ("third".equals(normalizeLyricProviderMode(lyricProvider))) {
                return qqThirdPartyLyric(songMid, songId, name, artist, album);
            }
            out.put("id", songId);
            out.put("mid", songMid);
            if (songMid.isEmpty() && songId.isEmpty()) {
                out.put("error", "Missing QQ song mid or id");
                out.put("lyric", "");
                out.put("tlyric", "");
                out.put("yrc", "");
                return out;
            }
            try {
                JSONObject param = new JSONObject();
                if (!songMid.isEmpty()) param.put("songMID", songMid);
                if (!songId.isEmpty()) param.put("songID", Long.parseLong(songId));
                JSONObject payload = new JSONObject();
                payload.put("comm", new JSONObject().put("ct", 24).put("cv", 0));
                payload.put("lyric", new JSONObject()
                        .put("module", "music.musichallSong.PlayLyricInfo")
                        .put("method", "GetPlayLyricInfo")
                        .put("param", param));
                String json = httpPost("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json", payload.toString(), "application/json; charset=UTF-8", qqHeaders());
                JSONObject root = new JSONObject(json);
                JSONObject lyric = root.optJSONObject("lyric");
                JSONObject data = lyric == null ? null : lyric.optJSONObject("data");
                lyricText = decodeQQLyricText(data == null ? "" : data.optString("lyric", ""));
                transText = decodeQQLyricText(data == null ? "" : data.optString("trans", ""));
                qrcText = decodeQQLyricText(data == null ? "" : data.optString("qrc", ""));
                romaText = decodeQQLyricText(data == null ? "" : data.optString("roma", ""));
            } catch (Exception e) {
                Log.w(TAG, "qq musicu lyric failed: " + e.getMessage());
            }
            if (lyricText.isEmpty() && !songMid.isEmpty()) {
                try {
                    JSONObject body = qqGetJson("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
                            queryMap(new String[][]{
                                    {"songmid", songMid},
                                    {"songtype", "0"},
                                    {"format", "json"},
                                    {"nobase64", "1"},
                                    {"g_tk", "5381"},
                                    {"loginUin", qqCookieUin(parseCookieString(qqCookie))},
                                    {"hostUin", "0"},
                                    {"inCharset", "utf8"},
                                    {"outCharset", "utf-8"},
                                    {"notice", "0"},
                                    {"platform", "yqq.json"},
                                    {"needNewCode", "0"}
                            }),
                            "https://y.qq.com/portal/player.html");
                    lyricText = decodeQQLyricText(body.optString("lyric", ""));
                    String legacyTrans = firstNonEmpty(body.optString("trans", ""), body.optString("tlyric", ""));
                    transText = firstNonEmpty(decodeQQLyricText(legacyTrans), transText);
                    source = "qq-legacy";
                } catch (Exception e) {
                    Log.w(TAG, "qq legacy lyric failed: " + e.getMessage());
                }
            }
            out.put("lyric", lyricText);
            out.put("tlyric", transText);
            out.put("yrc", qrcText);
            out.put("qrc", qrcText);
            out.put("roma", romaText);
            out.put("source", lyricText.isEmpty() && qrcText.isEmpty() ? "qq-empty" : source);
        } catch (Exception e) {
            try {
                out.put("provider", "qq");
                out.put("lyric", "");
                out.put("tlyric", "");
                out.put("yrc", "");
                out.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private String normalizeQQSongId(String id) {
        String raw = id == null ? "" : id.replaceAll("\\D", "");
        return raw.isEmpty() ? "" : raw;
    }

    private String decodeQQLyricText(String text) {
        String raw = decodeHtmlEntities(text == null ? "" : text.trim());
        if (raw.isEmpty()) return "";
        if ("0".equals(raw) || "null".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) return "";
        String compact = raw.replaceAll("\\s+", "");
        if (looksBase64(compact) && !raw.matches("(?s)^\\s*\\[.*")) {
            try {
                String decoded = new String(Base64.decode(compact, Base64.DEFAULT), StandardCharsets.UTF_8).replace("\uFEFF", "");
                if (!decoded.isEmpty() && (decoded.contains("[") || containsCjk(decoded))) raw = decoded;
            } catch (Exception e) {
                Log.w(TAG, "qq lyric base64 decode failed: " + e.getMessage());
            }
        }
        return decodeHtmlEntities(raw).replace("\r\n", "\n").trim();
    }

    private boolean looksBase64(String text) {
        if (text == null || text.length() < 8 || text.length() % 4 != 0) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '+' || ch == '/' || ch == '=') continue;
            return false;
        }
        return true;
    }

    private boolean containsCjk(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fa5') return true;
        }
        return false;
    }

    private String decodeHtmlEntities(String text) {
        String out = text == null ? "" : text;
        out = out.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&nbsp;", " ");
        java.util.regex.Matcher hex = java.util.regex.Pattern.compile("&#x([0-9a-fA-F]+);").matcher(out);
        StringBuffer sb = new StringBuffer();
        while (hex.find()) {
            String replacement;
            try {
                replacement = String.valueOf((char) Integer.parseInt(hex.group(1), 16));
            } catch (Exception e) {
                replacement = hex.group(0);
            }
            hex.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        hex.appendTail(sb);
        java.util.regex.Matcher dec = java.util.regex.Pattern.compile("&#(\\d+);").matcher(sb.toString());
        sb = new StringBuffer();
        while (dec.find()) {
            String replacement;
            try {
                replacement = String.valueOf((char) Integer.parseInt(dec.group(1), 10));
            } catch (Exception e) {
                replacement = dec.group(0);
            }
            dec.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        dec.appendTail(sb);
        return sb.toString();
    }

    private JSONObject searchQQ(String keywords, int limit, int page) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray mapped = new JSONArray();
        out.put("provider", "qq");
        out.put("songs", mapped);
        out.put("total", 0);
        out.put("pageNo", page);
        out.put("pageSize", limit);
        out.put("more", false);
        out.put("source", "empty");
        if (keywords == null || keywords.trim().isEmpty()) return out;
        Map<String, Boolean> seen = new HashMap<>();
        try {
            String endpoint = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
                    + "?format=json&outCharset=utf-8&inCharset=utf8&notice=0&platform=yqq.json&needNewCode=0"
                    + "&remoteplace=txt.yqq.song&catZhida=1&ct=24&qqmusic_ver=1298"
                    + "&n=" + limit + "&p=" + page + "&w=" + encode(keywords)
                    + "&aggr=1&cr=1&lossless=0&flag_qc=0&g_tk=5381&loginUin=0&hostUin=0&t=0";
            Map<String, String> headers = qqHeaders();
            headers.put("Referer", "https://c.y.qq.com/");
            String json = httpGet(endpoint, headers);
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            JSONObject songNode = data == null ? null : data.optJSONObject("song");
            JSONArray list = songNode == null ? null : songNode.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length() && mapped.length() < limit; i++) {
                    JSONObject item = mapQQPlaylistTrack(list.optJSONObject(i));
                    appendQQSearchSong(mapped, item, seen);
                }
                if (mapped.length() > 0) {
                    int total = songNode.optInt("totalnum", mapped.length());
                    out.put("total", total);
                    out.put("more", (page - 1) * limit + mapped.length() < total);
                    out.put("source", "client_search_cp");
                    return out;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "qq client search failed, fallback desktop: " + e.getMessage());
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("comm", new JSONObject().put("ct", 24).put("cv", 0));
            payload.put("search", new JSONObject()
                            .put("method", "DoSearchForQQMusicDesktop")
                            .put("module", "music.search.SearchCgiService")
                            .put("param", new JSONObject()
                                    .put("num_per_page", limit)
                                    .put("page_num", page)
                                    .put("query", keywords)
                                    .put("search_type", 0)));
            String json = httpPost("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json", payload.toString(), "application/json; charset=UTF-8", qqHeaders());
            JSONObject root = new JSONObject(json);
            JSONObject search = root.optJSONObject("search");
            JSONObject data = search == null ? null : search.optJSONObject("data");
            JSONObject body = data == null ? null : data.optJSONObject("body");
            JSONObject song = body == null ? null : body.optJSONObject("song");
            JSONArray list = song == null ? null : song.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length() && mapped.length() < limit; i++) {
                    JSONObject item = mapQQPlaylistTrack(list.optJSONObject(i));
                    appendQQSearchSong(mapped, item, seen);
                }
                if (mapped.length() > 0) {
                    JSONObject meta = song.optJSONObject("meta");
                    if (meta == null) meta = body == null ? null : body.optJSONObject("meta");
                    int total = meta == null ? mapped.length() : meta.optInt("sum", meta.optInt("total", mapped.length()));
                    out.put("total", total);
                    out.put("more", (page - 1) * limit + mapped.length() < total);
                    out.put("source", "desktop_search");
                    return out;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "qq desktop search failed, fallback smartbox: " + e.getMessage());
        }
        try {
            String endpoint = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg"
                    + "?format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
                    + "&key=" + encode(keywords);
            String json = httpGet(endpoint, qqHeaders());
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            JSONObject songNode = data == null ? null : data.optJSONObject("song");
            JSONArray list = songNode == null ? null : songNode.optJSONArray("itemlist");
            if (list == null) return out;
            for (int i = 0; i < list.length() && mapped.length() < limit; i++) {
                JSONObject raw = list.optJSONObject(i);
                if (raw == null) continue;
                JSONObject song = new JSONObject();
                String mid = raw.optString("mid", raw.optString("songmid"));
                String albumMid = raw.optString("albumMid", raw.optString("albummid"));
                song.put("provider", "qq");
                song.put("source", "qq");
                song.put("type", "song");
                song.put("id", raw.optString("id", mid));
                song.put("mid", mid);
                song.put("songmid", mid);
                song.put("mediaMid", raw.optString("mediaMid", mid));
                song.put("name", raw.optString("name"));
                song.put("artist", raw.optString("singer"));
                song.put("artists", new JSONArray());
                song.put("album", raw.optString("albumname", raw.optString("album")));
                song.put("cover", albumMid.isEmpty() ? "" : "https://y.qq.com/music/photo_new/T002R300x300M000" + albumMid + ".jpg");
                song.put("duration", 0);
                try {
                    JSONObject detail = qqSongDetail(mid, song);
                    if (detail != null && !detail.optString("name", "").isEmpty()) song = detail;
                } catch (Exception e) {
                    Log.w(TAG, "qq song detail failed: " + mid + " " + e.getMessage());
                }
                appendQQSearchSong(mapped, song, seen);
            }
        } catch (Exception e) {
            Log.w(TAG, "qq search failed: " + e.getMessage());
        }
        out.put("total", mapped.length());
        out.put("more", false);
        out.put("source", mapped.length() > 0 ? "smartbox" : "empty");
        return out;
    }

    private void appendQQSearchSong(JSONArray mapped, JSONObject song, Map<String, Boolean> seen) {
        if (mapped == null || song == null || seen == null) return;
        String name = song.optString("name", "");
        if (name.isEmpty()) return;
        String key = firstNonEmpty(song.optString("mid", ""), song.optString("songmid", ""), song.optString("id", ""));
        if (key.isEmpty()) key = name + "|" + song.optString("artist", "");
        if (seen.containsKey(key)) return;
        seen.put(key, true);
        mapped.put(song);
    }

    private JSONObject qqSongDetail(String mid, JSONObject fallback) throws Exception {
        if (mid == null || mid.trim().isEmpty()) return fallback;
        JSONObject payload = new JSONObject();
        payload.put("comm", new JSONObject().put("ct", 24).put("cv", 0));
        payload.put("songinfo", new JSONObject()
                .put("module", "music.pf_song_detail_svr")
                .put("method", "get_song_detail_yqq")
                .put("param", new JSONObject().put("song_mid", mid)));
        String json = httpPost("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json", payload.toString(), "application/json; charset=UTF-8", qqHeaders());
        JSONObject root = new JSONObject(json);
        JSONObject songInfo = root.optJSONObject("songinfo");
        JSONObject data = songInfo == null ? null : songInfo.optJSONObject("data");
        JSONObject track = data == null ? null : data.optJSONObject("track_info");
        JSONObject mapped = track == null ? null : mapQQPlaylistTrack(track);
        return mapped == null || mapped.optString("mid", "").isEmpty() ? fallback : mapped;
    }

    private JSONObject qqSongUrl(String mid, String mediaMid, String quality) {
        JSONObject out = new JSONObject();
        try {
            out.put("provider", "qq");
            out.put("requestedQuality", quality);
            if (mid == null || mid.trim().isEmpty()) {
                out.put("url", "");
                out.put("playable", false);
                out.put("error", "MISSING_MID");
                return out;
            }
            String media = (mediaMid == null || mediaMid.trim().isEmpty()) ? mid : mediaMid.trim();
            if (media.equals(mid)) {
                try {
                    JSONObject detail = qqSongDetail(mid, null);
                    String detailMediaMid = detail == null ? "" : detail.optString("mediaMid", "");
                    if (!detailMediaMid.isEmpty()) media = detailMediaMid;
                } catch (Exception e) {
                    Log.w(TAG, "qq song detail media_mid fallback failed: " + mid + " " + e.getMessage());
                }
            }
            Map<String, String> parsed = parseCookieString(qqCookie);
            String uin = qqCookieUin(parsed);
            if (uin.isEmpty()) uin = "0";
            String guid = firstNonEmpty(parsed.get("qqmusic_guid"), parsed.get("pgv_pvid"),
                    String.valueOf(10000000 + SECURE_RANDOM.nextInt(89999999)));
            String[] filenames = qqCandidateFilenames(media, quality);
            JSONObject lastInfo = null;
            JSONObject lastData = null;
            JSONObject payload = qqVkeyPayload(mid, filenames, uin, guid);
            String json = httpPost("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json", payload.toString(), "application/json; charset=UTF-8", qqHeaders());
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("req_0");
            data = data == null ? null : data.optJSONObject("data");
            JSONArray infos = data == null ? null : data.optJSONArray("midurlinfo");
            for (int i = 0; infos != null && i < infos.length(); i++) {
                JSONObject info = infos.optJSONObject(i);
                String purl = info == null ? "" : info.optString("purl", "");
                lastInfo = info;
                lastData = data;
                if (purl.isEmpty()) continue;
                String filename = firstNonEmpty(info.optString("filename", ""), i < filenames.length ? filenames[i] : "");
                String playableUrl = firstPlayableQQAudioUrl(data, purl);
                if (playableUrl.isEmpty()) continue;
                out.put("url", playableUrl);
                out.put("playable", true);
                out.put("trial", false);
                out.put("level", qqQualityFromFilename(filename, quality));
                out.put("quality", qqQualityFromFilename(filename, quality));
                out.put("filename", filename);
                out.put("uin", uin);
                return out;
            }
            out.put("url", "");
            out.put("playable", false);
            out.put("error", "QQ_URL_UNAVAILABLE");
            out.put("uin", uin);
            out.put("message", lastInfo == null ? "QQ Music returned no playable URL." : lastInfo.optString("msg", "QQ Music returned no playable URL."));
            if (lastData != null) out.put("serverCode", lastData.optInt("code", 0));
        } catch (Exception e) {
            try {
                out.put("url", "");
                out.put("playable", false);
                out.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private JSONObject qqVkeyPayload(String mid, String[] filenames, String uin, String guid) throws Exception {
        if (filenames == null || filenames.length == 0) filenames = new String[0];
        JSONObject payload = new JSONObject();
        JSONObject comm = new JSONObject();
        comm.put("uin", uin);
        comm.put("format", "json");
        comm.put("ct", 24);
        comm.put("cv", 0);
        payload.put("comm", comm);
        JSONObject param = new JSONObject();
        param.put("guid", guid);
        JSONArray songmids = new JSONArray();
        JSONArray songtypes = new JSONArray();
        JSONArray filenameArray = new JSONArray();
        int count = Math.max(1, filenames.length);
        for (int i = 0; i < count; i++) {
            songmids.put(mid);
            songtypes.put(0);
            if (i < filenames.length) filenameArray.put(filenames[i]);
        }
        param.put("songmid", songmids);
        param.put("songtype", songtypes);
        param.put("uin", uin);
        param.put("loginflag", 1);
        param.put("platform", "20");
        if (filenameArray.length() > 0) param.put("filename", filenameArray);
        JSONObject req = new JSONObject();
        req.put("module", "vkey.GetVkeyServer");
        req.put("method", "CgiGetVkey");
        req.put("param", param);
        payload.put("req_0", req);
        return payload;
    }

    private String[] qqCandidateFilenames(String mediaMid, String quality) {
        String media = mediaMid == null ? "" : mediaMid.trim();
        String q = quality == null ? "" : quality.toLowerCase(Locale.US);
        if (q.contains("hires") || q.contains("jymaster")) {
            return new String[]{"RS01" + media + ".flac", "F000" + media + ".flac", "M800" + media + ".mp3", "M500" + media + ".mp3", "C400" + media + ".m4a"};
        }
        if (q.contains("lossless")) {
            return new String[]{"F000" + media + ".flac", "M800" + media + ".mp3", "M500" + media + ".mp3", "C400" + media + ".m4a"};
        }
        if (q.contains("exhigh") || q.contains("higher")) {
            return new String[]{"M800" + media + ".mp3", "M500" + media + ".mp3", "C400" + media + ".m4a"};
        }
        return new String[]{"M500" + media + ".mp3", "C400" + media + ".m4a", "M800" + media + ".mp3"};
    }

    private String qqQualityFromFilename(String filename, String fallback) {
        String file = filename == null ? "" : filename.toUpperCase(Locale.US);
        if (file.startsWith("RS01")) return "hires";
        if (file.startsWith("F000")) return "lossless";
        if (file.startsWith("M800")) return "exhigh";
        if (file.startsWith("M500")) return "standard";
        if (file.startsWith("C400")) return "standard";
        return fallback == null || fallback.isEmpty() ? "standard" : fallback;
    }

    private String firstPlayableQQAudioUrl(JSONObject data, String purl) {
        if (purl == null || purl.trim().isEmpty()) return "";
        List<String> bases = new ArrayList<>();
        JSONArray sip = data == null ? null : data.optJSONArray("sip");
        if (sip != null) {
            for (int i = 0; i < sip.length(); i++) {
                String base = sip.optString(i, "");
                if (!base.isEmpty() && !bases.contains(base)) bases.add(base);
            }
        }
        String[] fallbacks = new String[]{
                "https://ws.stream.qqmusic.qq.com/",
                "http://ws.stream.qqmusic.qq.com/",
                "https://dl.stream.qqmusic.qq.com/",
                "http://dl.stream.qqmusic.qq.com/",
                "https://isure.stream.qqmusic.qq.com/",
                "http://isure.stream.qqmusic.qq.com/"
        };
        for (String fallback : fallbacks) {
            if (!bases.contains(fallback)) bases.add(fallback);
        }
        for (String base : bases) {
            String candidate = joinUrl(base, purl);
            if (isPlayableAudioUrl(candidate)) return candidate;
        }
        return "";
    }

    private String joinUrl(String base, String path) {
        String left = base == null ? "" : base;
        String right = path == null ? "" : path;
        if (left.endsWith("/") || right.startsWith("/")) return left + right;
        return left + "/" + right;
    }

    private boolean isPlayableAudioUrl(String sourceUrl) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(sourceUrl).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(9000);
            applyProxyHeaders(conn, sourceUrl, null);
            conn.setRequestProperty("Range", "bytes=0-1");
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) return false;
            String type = conn.getContentType();
            String lower = type == null ? "" : type.toLowerCase(Locale.US);
            return !lower.contains("json") && !lower.startsWith("text/");
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void proxy(OutputStream output, String sourceUrl, Map<String, String> requestHeaders, boolean audio) throws IOException {
        if (sourceUrl == null || !sourceUrl.matches("(?i)^https?://.*")) {
            sendText(output, 400, "text/plain; charset=utf-8", "Invalid upstream URL");
            return;
        }
        HttpURLConnection conn = null;
        try {
            conn = openConnection(sourceUrl, requestHeaders);
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) stream = emptyStream();
            Map<String, String> headers = new HashMap<>();
            String type = conn.getContentType();
            if (code >= 400) headers.put("Content-Type", type == null ? "text/plain; charset=utf-8" : type);
            else if (type != null) headers.put("Content-Type", audio ? audioContentType(sourceUrl, type) : type);
            else headers.put("Content-Type", audio ? audioContentType(sourceUrl, "") : "application/octet-stream");
            String length = conn.getHeaderField("Content-Length");
            String range = conn.getHeaderField("Content-Range");
            if (length != null) headers.put("Content-Length", length);
            if (range != null) headers.put("Content-Range", range);
            headers.put("Accept-Ranges", "bytes");
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cross-Origin-Resource-Policy", "cross-origin");
            writeStatusAndHeaders(output, code, headers);
            copy(stream, output);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection openConnection(String sourceUrl, Map<String, String> requestHeaders) throws IOException {
        String current = sourceUrl;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            applyProxyHeaders(conn, current, requestHeaders);
            String range = header(requestHeaders, "range");
            if (range != null && !range.isEmpty()) conn.setRequestProperty("Range", range);
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) return conn;
                current = new URL(new URL(current), location).toString();
                continue;
            }
            return conn;
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
        applyProxyHeaders(conn, current, requestHeaders);
        return conn;
    }

    private void applyProxyHeaders(HttpURLConnection conn, String sourceUrl, Map<String, String> requestHeaders) {
        conn.setRequestProperty("User-Agent", isQQUrl(sourceUrl) ? QQ_DESKTOP_USER_AGENT : USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Referer", proxyReferer(sourceUrl));
        String origin = proxyOrigin(sourceUrl);
        if (!origin.isEmpty()) conn.setRequestProperty("Origin", origin);
        if (isQQUrl(sourceUrl) && qqCookie != null && !qqCookie.trim().isEmpty()) {
            conn.setRequestProperty("Cookie", qqCookie);
        } else if (isNeteaseUrl(sourceUrl) && neteaseCookie != null && !neteaseCookie.trim().isEmpty()) {
            conn.setRequestProperty("Cookie", neteaseCookie);
        }
        String accept = header(requestHeaders, "accept");
        if (accept != null && !accept.isEmpty()) conn.setRequestProperty("Accept", accept);
    }

    private String proxyReferer(String sourceUrl) {
        if (isQQUrl(sourceUrl)) return "https://y.qq.com/";
        if (isNeteaseUrl(sourceUrl)) return "https://music.163.com/";
        return "https://y.qq.com/";
    }

    private String proxyOrigin(String sourceUrl) {
        if (isQQUrl(sourceUrl)) return "https://y.qq.com";
        if (isNeteaseUrl(sourceUrl)) return "https://music.163.com";
        return "";
    }

    private boolean isQQUrl(String sourceUrl) {
        String host = urlHost(sourceUrl);
        return host.endsWith("qq.com") || host.endsWith("qqmusic.qq.com");
    }

    private boolean isNeteaseUrl(String sourceUrl) {
        String host = urlHost(sourceUrl);
        return host.endsWith("163.com") || host.endsWith("126.net");
    }

    private String urlHost(String sourceUrl) {
        try {
            return new URL(sourceUrl).getHost().toLowerCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    private String httpGet(String target, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        try {
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(18000);
            conn.setRequestMethod("GET");
            applyCommonHeaders(conn);
            applyHeaders(conn, headers);
            return new String(readAll(responseStream(conn)), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private String httpPost(String target, String body, String contentType, Map<String, String> headers) throws IOException {
        return httpPostResult(target, body, contentType, headers).body;
    }

    private HttpResult httpPostResult(String target, String body, String contentType, Map<String, String> headers) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        try {
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            applyCommonHeaders(conn);
            applyHeaders(conn, headers);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            OutputStream out = conn.getOutputStream();
            out.write(bytes);
            out.close();
            String response = new String(readAll(responseStream(conn)), StandardCharsets.UTF_8);
            return new HttpResult(conn.getResponseCode(), response, collectSetCookie(conn));
        } finally {
            conn.disconnect();
        }
    }

    private HttpResult neteaseWeapiPost(String path, JSONObject data, boolean withSavedCookie) throws Exception {
        JSONObject payload = data == null ? new JSONObject() : new JSONObject(data.toString());
        payload.put("csrf_token", withSavedCookie ? neteaseCsrfToken() : "");
        String form = neteaseWeapiForm(payload);
        Map<String, String> headers = withSavedCookie ? neteaseHeaders() : new HashMap<String, String>();
        headers.put("Referer", "https://music.163.com/");
        headers.put("Origin", "https://music.163.com");
        return httpPostResult(
                "https://music.163.com/weapi" + path,
                form,
                "application/x-www-form-urlencoded",
                headers);
    }

    private boolean hasNeteaseMusicU() {
        return parseCookieString(neteaseCookie).containsKey("MUSIC_U");
    }

    private String neteaseCsrfToken() {
        return firstNonEmpty(parseCookieString(neteaseCookie).get("__csrf"), parseCookieString(neteaseCookie).get("csrf_token"));
    }

    private String neteaseWeapiForm(JSONObject data) throws Exception {
        String text = data == null ? "{}" : data.toString();
        String secret = randomNeteaseSecret();
        String encrypted = aesCbcEncrypt(aesCbcEncrypt(text, NETEASE_WEAPI_KEY, NETEASE_WEAPI_IV), secret, NETEASE_WEAPI_IV);
        String encSecKey = rsaEncryptNetease(new StringBuilder(secret).reverse().toString());
        return "params=" + encode(encrypted) + "&encSecKey=" + encode(encSecKey);
    }

    private String aesCbcEncrypt(String text, String key, String iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String rsaEncryptNetease(String text) {
        BigInteger message = new BigInteger(1, text.getBytes(StandardCharsets.UTF_8));
        BigInteger exponent = new BigInteger(NETEASE_RSA_PUBLIC_EXPONENT, 16);
        BigInteger modulus = new BigInteger(NETEASE_RSA_MODULUS, 16);
        String hex = message.modPow(exponent, modulus).toString(16);
        while (hex.length() < 256) hex = "0" + hex;
        return hex;
    }

    private String randomNeteaseSecret() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            out.append(NETEASE_BASE62.charAt(SECURE_RANDOM.nextInt(NETEASE_BASE62.length())));
        }
        return out.toString();
    }

    private String collectSetCookie(HttpURLConnection conn) {
        if (conn == null || conn.getHeaderFields() == null) return "";
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
            String name = entry.getKey();
            if (name == null || !"set-cookie".equalsIgnoreCase(name)) continue;
            List<String> cookies = entry.getValue();
            if (cookies == null) continue;
            for (String raw : cookies) {
                if (raw == null || raw.trim().isEmpty()) continue;
                String clean = raw.trim();
                int semi = clean.indexOf(';');
                if (semi > 0) clean = clean.substring(0, semi).trim();
                if (!clean.isEmpty()) values.add(clean);
            }
        }
        return join(values, "; ");
    }

    private void applyCommonHeaders(HttpURLConnection conn) {
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Referer", "https://music.163.com/");
        conn.setRequestProperty("Origin", "https://music.163.com");
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
    }

    private void applyHeaders(HttpURLConnection conn, Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
    }

    private Map<String, String> qqHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", QQ_DESKTOP_USER_AGENT);
        headers.put("Referer", "https://y.qq.com/");
        headers.put("Origin", "https://y.qq.com");
        if (qqCookie != null && !qqCookie.trim().isEmpty()) headers.put("Cookie", qqCookie);
        return headers;
    }

    private Map<String, String> neteaseHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://music.163.com/");
        headers.put("Origin", "https://music.163.com");
        if (neteaseCookie != null && !neteaseCookie.trim().isEmpty()) headers.put("Cookie", neteaseCookie);
        return headers;
    }

    private String normalizeCookieHeader(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return "";
        text = text.replace('\r', ';').replace('\n', ';');
        String[] parts = text.split(";");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String clean = part == null ? "" : part.trim();
            if (clean.isEmpty() || clean.indexOf('=') <= 0) continue;
            if (out.length() > 0) out.append("; ");
            out.append(clean);
        }
        return out.toString();
    }

    private Map<String, String> parseCookieString(String cookie) {
        Map<String, String> out = new HashMap<>();
        if (cookie == null || cookie.trim().isEmpty()) return out;
        String[] parts = cookie.split(";");
        for (String part : parts) {
            if (part == null) continue;
            int idx = part.indexOf('=');
            if (idx <= 0) continue;
            String key = part.substring(0, idx).trim();
            String value = part.substring(idx + 1).trim();
            if (!key.isEmpty()) out.put(key, value);
        }
        return out;
    }

    private String qqCookieMusicKey(Map<String, String> parsed) {
        if (parsed == null) return "";
        return firstNonEmpty(
                parsed.get("qm_keyst"),
                parsed.get("qqmusic_key"),
                parsed.get("music_key"),
                parsed.get("p_skey"),
                parsed.get("skey"),
                parsed.get("psrf_qqaccess_token"),
                parsed.get("wxskey"));
    }

    private String qqCookiePlaybackKey(Map<String, String> parsed) {
        if (parsed == null) return "";
        return firstNonEmpty(
                parsed.get("qm_keyst"),
                parsed.get("qqmusic_key"),
                parsed.get("music_key"),
                parsed.get("p_skey"),
                parsed.get("skey"),
                parsed.get("wxskey"));
    }

    private String qqGToken() {
        Map<String, String> parsed = parseCookieString(qqCookie);
        String key = firstNonEmpty(
                parsed.get("p_skey"),
                parsed.get("skey"),
                parsed.get("qqmusic_key"),
                parsed.get("qm_keyst"),
                parsed.get("music_key"),
                parsed.get("wxskey"));
        long hash = 5381L;
        for (int i = 0; i < key.length(); i++) {
            hash += (hash << 5) + key.charAt(i);
        }
        return String.valueOf(hash & 0x7fffffffL);
    }

    private String qqCookieUin(Map<String, String> parsed) {
        if (parsed == null) return "";
        String raw = "2".equals(parsed.get("login_type"))
                ? firstNonEmpty(parsed.get("wxuin"), parsed.get("uin"), parsed.get("p_uin"))
                : firstNonEmpty(parsed.get("uin"), parsed.get("qqmusic_uin"), parsed.get("wxuin"), parsed.get("p_uin"));
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    private int neteaseBrForQuality(String quality) {
        String q = quality == null ? "" : quality.toLowerCase(Locale.US);
        if (q.contains("lossless") || q.contains("hires") || q.contains("jymaster")) return 999000;
        if (q.contains("higher") || q.contains("exhigh") || q.contains("standard")) return 320000;
        return 320000;
    }

    private JSONObject qqGetJson(String target, Map<String, String> params, String referer) throws Exception {
        StringBuilder url = new StringBuilder(target);
        if (params != null && !params.isEmpty()) {
            url.append(target.contains("?") ? "&" : "?");
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) url.append('&');
                first = false;
                url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
        Map<String, String> headers = qqHeaders();
        if (referer != null && !referer.isEmpty()) headers.put("Referer", referer);
        String text = httpGet(url.toString(), headers);
        return parseJsonOrJsonp(text);
    }

    private JSONObject parseJsonOrJsonp(String text) throws Exception {
        String raw = text == null ? "" : text.trim();
        int open = raw.indexOf('(');
        int close = raw.lastIndexOf(')');
        if (open > 0 && close > open && raw.substring(0, open).matches("[A-Za-z0-9_$.]+")) {
            raw = raw.substring(open + 1, close).trim();
            if (raw.endsWith(";")) raw = raw.substring(0, raw.length() - 1).trim();
        }
        return new JSONObject(raw.isEmpty() ? "{}" : raw);
    }

    private Map<String, String> queryMap(String[][] entries) {
        Map<String, String> out = new HashMap<>();
        if (entries == null) return out;
        for (String[] entry : entries) {
            if (entry == null || entry.length < 2 || entry[0] == null || entry[1] == null) continue;
            out.put(entry[0], entry[1]);
        }
        return out;
    }

    private JSONObject firstObject(JSONObject parent, String[] keys) {
        if (parent == null || keys == null) return null;
        for (String key : keys) {
            JSONObject value = parent.optJSONObject(key);
            if (value != null) return value;
        }
        return null;
    }

    private JSONArray firstArray(JSONObject parent, String[] keys) {
        if (parent == null || keys == null) return null;
        for (String key : keys) {
            JSONArray value = parent.optJSONArray(key);
            if (value != null) return value;
        }
        return null;
    }

    private int firstPositiveInt(JSONObject raw, String[] keys) {
        return (int) Math.min(Integer.MAX_VALUE, firstPositiveLong(raw, keys));
    }

    private long firstPositiveLong(JSONObject raw, String[] keys) {
        if (raw == null || keys == null) return 0;
        for (String key : keys) {
            long value = raw.optLong(key, 0);
            if (value > 0) return value;
            String text = raw.optString(key, "");
            long parsed = safeLong(text, 0);
            if (parsed > 0) return parsed;
        }
        return 0;
    }

    private String joinArtistNames(JSONArray artists) {
        List<String> names = new ArrayList<>();
        if (artists != null) {
            for (int i = 0; i < artists.length(); i++) {
                JSONObject artist = artists.optJSONObject(i);
                if (artist != null && !artist.optString("name", "").isEmpty()) names.add(artist.optString("name", ""));
            }
        }
        return join(names, " / ");
    }

    private InputStream responseStream(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        return stream == null ? emptyStream() : stream;
    }

    private RequestUrl parseRequestTarget(String target) {
        String raw = target == null ? "/" : target;
        int hash = raw.indexOf('#');
        if (hash >= 0) raw = raw.substring(0, hash);
        int q = raw.indexOf('?');
        String path = q >= 0 ? raw.substring(0, q) : raw;
        String query = q >= 0 ? raw.substring(q + 1) : "";
        return new RequestUrl(path.isEmpty() ? "/" : path, parseQuery(query));
    }

    private Map<String, List<String>> parseQuery(String query) {
        Map<String, List<String>> out = new HashMap<>();
        if (query == null || query.isEmpty()) return out;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String key = decode(eq >= 0 ? pair.substring(0, eq) : pair);
            String value = decode(eq >= 0 ? pair.substring(eq + 1) : "");
            if (!out.containsKey(key)) out.put(key, new ArrayList<String>());
            out.get(key).add(value);
        }
        return out;
    }

    private Map<String, String> readHeaders(BufferedInputStream input) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(input)) != null) {
            if (line.isEmpty()) break;
            int idx = line.indexOf(':');
            if (idx <= 0) continue;
            headers.put(line.substring(0, idx).trim().toLowerCase(Locale.US), line.substring(idx + 1).trim());
        }
        return headers;
    }

    private byte[] readBody(BufferedInputStream input, Map<String, String> headers) throws IOException {
        int length = clampInt(header(headers, "content-length"), 0, 4 * 1024 * 1024, 0);
        if (length <= 0) return new byte[0];
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(body, offset, length - offset);
            if (read < 0) break;
            offset += read;
        }
        if (offset == length) return body;
        byte[] shortBody = new byte[offset];
        System.arraycopy(body, 0, shortBody, 0, offset);
        return shortBody;
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        int b;
        boolean seenAny = false;
        while ((b = input.read()) != -1) {
            seenAny = true;
            if (b == '\n') break;
            if (b != '\r') out.write(b);
        }
        if (!seenAny && out.size() == 0) return null;
        return out.toString("UTF-8");
    }

    private void sendJson(OutputStream output, int status, JSONObject json) throws IOException {
        Map<String, String> headers = corsHeaders();
        headers.put("Cache-Control", "no-store, no-cache, must-revalidate");
        sendBytes(output, status, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8), headers);
    }

    private void sendText(OutputStream output, int status, String type, String text) throws IOException {
        sendBytes(output, status, type, text.getBytes(StandardCharsets.UTF_8), corsHeaders());
    }

    private void sendBytes(OutputStream output, int status, String contentType, byte[] bytes, Map<String, String> extraHeaders) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Content-Length", String.valueOf(bytes == null ? 0 : bytes.length));
        headers.put("Connection", "close");
        headers.putAll(extraHeaders == null ? new HashMap<String, String>() : extraHeaders);
        writeStatusAndHeaders(output, status, headers);
        if (bytes != null && bytes.length > 0) output.write(bytes);
        output.flush();
    }

    private void writeStatusAndHeaders(OutputStream output, int status, Map<String, String> headers) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n");
        if (!headers.containsKey("Connection")) headers.put("Connection", "close");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getValue() != null) {
                head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        head.append("\r\n");
        output.write(head.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JSONObject jsonError(String message) throws Exception {
        JSONObject out = new JSONObject();
        out.put("error", message == null ? "ERROR" : message);
        return out;
    }

    private Map<String, String> corsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type,Range,Accept,Origin,Referer");
        return headers;
    }

    private String mimeType(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".bin")) return "application/octet-stream";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".m4a")) return "audio/mp4";
        if (p.endsWith(".flac")) return "audio/flac";
        if (p.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    private String audioContentType(String sourceUrl, String upstream) {
        if (upstream != null && upstream.toLowerCase(Locale.US).startsWith("audio/")) return upstream;
        String p = sourceUrl.toLowerCase(Locale.US);
        if (p.contains(".flac")) return "audio/flac";
        if (p.contains(".m4a") || p.contains(".mp4")) return "audio/mp4";
        if (p.contains(".wav")) return "audio/wav";
        return "audio/mpeg";
    }

    private String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private static int clampInt(String value, int min, int max, int fallback) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long safeLong(String value, long fallback) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isNumericString(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String clean = value.trim();
        for (int i = 0; i < clean.length(); i++) {
            char ch = clean.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        return headers.get(name.toLowerCase(Locale.US));
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(input, out);
            return out.toByteArray();
        } finally {
            closeQuietly(input);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static String join(List<String> values, String sep) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            if (out.length() > 0) out.append(sep);
            out.append(value);
        }
        return out.toString();
    }

    private static void closeQuietly(Object target) {
        if (target == null) return;
        try {
            if (target instanceof Closeable) ((Closeable) target).close();
            else if (target instanceof Socket) ((Socket) target).close();
            else if (target instanceof ServerSocket) ((ServerSocket) target).close();
        } catch (IOException ignored) {
        }
    }

    private static InputStream emptyStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    private static class HttpResult {
        final int statusCode;
        final String body;
        final String setCookie;

        HttpResult(int statusCode, String body, String setCookie) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
            this.setCookie = setCookie == null ? "" : setCookie;
        }
    }

    private static class RequestUrl {
        final String path;
        final Map<String, List<String>> query;

        RequestUrl(String path, Map<String, List<String>> query) {
            this.path = path;
            this.query = query;
        }

        String param(String key, String fallback) {
            List<String> values = query.get(key);
            return values == null || values.isEmpty() ? fallback : values.get(0);
        }
    }
}
