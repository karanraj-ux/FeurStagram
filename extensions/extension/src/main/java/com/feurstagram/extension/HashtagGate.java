package com.feurstagram.extension;

import android.os.Environment;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.CharArrayReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hashtag-based feed filter.
 *
 * <p>HOW IT WORKS (v2): the patch hooks Instagram's JSON parser factories
 * (methods taking a raw JSON String and returning the obfuscated parser
 * object). {@link #filterFeedJson} runs BEFORE parsing, on the real JSON
 * string, where the full caption text is present. Non-matching media items
 * are removed from the "items" array, so they never enter the feed.
 *
 * <p>Why this works despite Instagram hiding hashtags behind "more": the hook
 * runs at the JSON deserialisation layer, where the <b>full</b> caption text is
 * present. The collapsed caption is purely a UI rendering concern one layer up.
 *
 * <p>FAIL-OPEN BY DESIGN: any exception, any missing/unparseable JSON, any
 * item with no classifiable hashtags returns the input unchanged, so the feed
 * renders exactly as Instagram intended. Nothing here can crash the app or
 * empty the feed.
 */
public final class HashtagGate {

    private HashtagGate() {}

    private static final String TAG = "FeurHashtag";

    private static final Pattern HASHTAG = Pattern.compile("#[\\p{L}\\p{N}_]+");

    /** Diagnostic: count of feed-like JSONs seen (for rate-limited logging). */
    private static volatile int sFeedSeenCount;

    /** Diagnostic: count of ALL factory calls (for rate-limited logging). */
    private static volatile int sTotalCalls;

    /**
     * Surgical diagnostic: log which factory method called us, via stack trace.
     * Returns e.g. "X.01yu.B3N" or "unknown".
     */
    private static String callerFactory() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (StackTraceElement e : stack) {
                String cls = e.getClassName();
                // Skip our own frames and system frames
                if (cls.startsWith("com.feurstagram.")) continue;
                if (cls.startsWith("java.")) continue;
                if (cls.startsWith("android.")) continue;
                if (cls.startsWith("dalvik.")) continue;
                // First app frame is the factory (e.g., X.01yu) or its caller
                return cls + "." + e.getMethodName();
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }

    /**
     * Surgical diagnostic: log the full call chain (first 12 app frames).
     */
    private static void logStack() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder("diag: stack=[");
            int n = 0;
            for (StackTraceElement e : stack) {
                String cls = e.getClassName();
                if (cls.startsWith("com.feurstagram.")) continue;
                if (cls.startsWith("java.lang.Thread")) continue;
                if (n > 0) sb.append(" <- ");
                // Shorten: X.01yu.B3N -> 01yu.B3N
                String shortCls = cls.startsWith("X.") ? cls.substring(2) : cls;
                sb.append(shortCls).append(".").append(e.getMethodName());
                if (++n >= 12) break;
            }
            sb.append("]");
            diagLog(sb.toString());
        } catch (Throwable ignored) {}
    }

    /**
     * Write a diagnostic line to /sdcard/Download/FeurHashtag.log so it can be
     * read via Termux (cat /sdcard/Download/FeurHashtag.log) without ADB.
     * Fail-silent: never crashes the app if storage is unavailable.
     */
    private static void fileLog(String msg) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return;
            File log = new File(dir, "FeurHashtag.log");
            FileWriter w = new FileWriter(log, true);
            try {
                w.write(msg + "\n");
            } finally {
                w.close();
            }
        } catch (IOException | SecurityException ignored) {
        }
    }

    /** Log to both logcat and the file. */
    private static void diagLog(String msg) {
        Log.i(TAG, msg);
        fileLog(msg);
    }

    // ==================== EDIT YOUR HASHTAGS HERE ====================
    // Category id -> lowercase hashtags (with '#'). Matched against the
    // caption's hashtags after lowercasing, so keep this list lowercase.
    private static final Map<String, Set<String>> CATEGORY_TAGS = new HashMap<>();
    private static final Map<String, String> CATEGORY_PREF_KEYS = new HashMap<>();
    static {
        CATEGORY_TAGS.put("education", tags(
                "#education", "#study", "#studytok", "#studygram", "#learning",
                "#student", "#students", "#exam", "#exams", "#upsc", "#neet", "#jee",
                "#school", "#college", "#university", "#onlinelearning", "#elearning",
                "#studymotivation", "#notes", "#studynotes", "#science", "#maths",
                "#history", "#physics", "#chemistry"));
        CATEGORY_PREF_KEYS.put("education", "hashtag_cat_education");

        CATEGORY_TAGS.put("tech", tags(
                "#tech", "#technology", "#programming", "#coding", "#coder",
                "#developer", "#developers", "#software", "#softwareengineer",
                "#python", "#javascript", "#java", "#kotlin", "#ai",
                "#artificialintelligence", "#machinelearning", "#datascience",
                "#cybersecurity", "#webdev", "#android", "#androiddev",
                "#technews", "#gadgets", "#startup", "#buildinpublic"));
        CATEGORY_PREF_KEYS.put("tech", "hashtag_cat_tech");
    }

    private static Set<String> tags(String... ts) {
        Set<String> s = new HashSet<>();
        for (String t : ts) s.add(t);
        return s;
    }
    // ================================================================

    /**
     * Patch entry point. Called with the raw JSON response string BEFORE
     * Instagram parses it. Removes feed items whose caption hashtags match none
     * of the enabled categories, and returns the (possibly modified) JSON.
     * Never throws; fails open by returning the input unchanged.
     */
    public static String filterFeedJson(String json) {
        if (json == null) return null;
        // SURGICAL DIAGNOSTIC: log every factory call with the calling factory
        // name and a snippet. Rate-limited to first 40 to avoid log spam.
        // This tells us exactly which factories fire, for what data, and when.
        int callNum = ++sTotalCalls;
        boolean isFeedLike = json.contains("feed_items") || json.contains("media_or_ad");
        if (callNum <= 40) {
            String factory = callerFactory();
            String snippet = json.length() > 200 ? json.substring(0, 200) : json;
            // Compact the snippet: remove newlines
            snippet = snippet.replace('\n', ' ').replace('\r', ' ');
            diagLog("diag: CALL #" + callNum + " factory=[" + factory + "] feedLike=" + isFeedLike + " snippet=[" + snippet + "]");
            if (isFeedLike) {
                sFeedSeenCount++;
                logStack();
            }
        } else if (isFeedLike && sFeedSeenCount < 20) {
            sFeedSeenCount++;
            String snippet = json.length() > 300 ? json.substring(0, 300) : json;
            diagLog("diag: FEED JSON #" + sFeedSeenCount + " snippet=[" + snippet + "]");
        }
        try {
            if (!Config.getBlocked("hashtag_filter_enabled", false)) return json;
            // Fast path: not a feed-like response.
            if (!isFeedLike) return json;

            JSONObject root = new JSONObject(json);

            // Diagnostic: log top-level keys for the first few feed JSONs
            if (sFeedSeenCount <= 3) {
                StringBuilder keys = new StringBuilder();
                Iterator<String> it = root.keys();
                while (it.hasNext()) {
                    if (keys.length() > 0) keys.append(",");
                    keys.append(it.next());
                }
                diagLog("diag: top-level keys=[" + keys + "]");
            }

            // The timeline API uses "feed_items"; other endpoints may use "items".
            JSONArray items = root.optJSONArray("feed_items");
            if (items == null) items = root.optJSONArray("items");
            if (items == null) return json;

            int total = items.length();
            int removed = 0;
            for (int i = total - 1; i >= 0; i--) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                JSONObject media = item.optJSONObject("media_or_ad");
                if (media == null) continue; // not a media item: keep

                String caption = "";
                JSONObject capObj = media.optJSONObject("caption");
                if (capObj != null) caption = capObj.optString("text", "");
                if (caption.isEmpty()) continue; // unclassifiable: fail open

                Set<String> tags = hashtagsIn(caption);
                if (tags.isEmpty()) continue; // no hashtags: fail open

                if (!matchesEnabledCategories(tags)) {
                    items.remove(i);
                    removed++;
                    if (removed <= 5) diagLog("drop [media_or_ad] tags=" + tags);
                }
            }
            if (removed > 0) {
                diagLog("feed filter: removed " + removed + "/" + total + " items");
                return root.toString();
            }
            return json;
        } catch (Throwable t) {
            Log.w(TAG, "filterFeedJson fail-open", t);
            return json;
        }
    }

    /** True if any of the caption's hashtags is in an enabled category. */
    private static boolean matchesEnabledCategories(Set<String> tags) {
        for (Map.Entry<String, Set<String>> e : CATEGORY_TAGS.entrySet()) {
            String prefKey = CATEGORY_PREF_KEYS.get(e.getKey());
            if (prefKey == null || !Config.getBlocked(prefKey, true)) continue;
            for (String t : tags) {
                if (e.getValue().contains(t)) return true;
            }
        }
        return false;
    }

    /** Lower-cased hashtags found in the caption. */
    private static Set<String> hashtagsIn(String caption) {
        Set<String> out = new HashSet<>();
        Matcher m = HASHTAG.matcher(caption);
        while (m.find()) out.add(m.group().toLowerCase(Locale.ROOT));
        return out;
    }

    // ==================== Non-String factory wrappers ====================
    // These are called by the patch hooks on InputStream/byte[]/Reader/char[]
    // parser factories. They convert to String, run filterFeedJson, and convert
    // back. All fail-open: on any error, return the input unchanged.

    /** For InputStream-based factories (e.g., B3M, A0E, A0A). */
    public static InputStream filterFeedStream(InputStream in) {
        if (in == null) return null;
        try {
            String json = readStream(in);
            String filtered = filterFeedJson(json);
            // Always return a fresh stream: the original is consumed by readStream.
            return new ByteArrayInputStream(filtered.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "filterFeedStream fail-open", t);
            return in;
        }
    }

    /** For byte[]-based factories (e.g., A0F). */
    public static byte[] filterFeedBytes(byte[] data) {
        if (data == null) return null;
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            String filtered = filterFeedJson(json);
            if (filtered == json) return data; // unchanged
            return filtered.getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "filterFeedBytes fail-open", t);
            return data;
        }
    }

    /**
     * For byte[]-with-offset factories (e.g., A0C with [B I).
     * Filters the bytes from offset, returns a new array; caller sets offset to 0.
     */
    public static byte[] filterFeedBytesWithOffset(byte[] data, int offset) {
        if (data == null) return null;
        try {
            int len = data.length - offset;
            if (len <= 0) return data;
            String json = new String(data, offset, len, StandardCharsets.UTF_8);
            String filtered = filterFeedJson(json);
            if (filtered == json) return data; // unchanged
            return filtered.getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "filterFeedBytesWithOffset fail-open", t);
            return data;
        }
    }

    /** For Reader-based factories (e.g., A0B). */
    public static Reader filterFeedReader(Reader reader) {
        if (reader == null) return null;
        try {
            String json = readReader(reader);
            String filtered = filterFeedJson(json);
            // Always return a fresh reader: the original is consumed by readReader.
            return new StringReader(filtered);
        } catch (Throwable t) {
            Log.w(TAG, "filterFeedReader fail-open", t);
            return reader;
        }
    }

    /**
     * For char[]-with-offset factories (e.g., A0D with [C I).
     * Filters the chars from offset, returns a new array; caller sets offset to 0.
     */
    public static char[] filterFeedChars(char[] data, int offset) {
        if (data == null) return null;
        try {
            int len = data.length - offset;
            if (len <= 0) return data;
            String json = new String(data, offset, len);
            String filtered = filterFeedJson(json);
            if (filtered == json) return data; // unchanged
            return filtered.toCharArray();
        } catch (Throwable t) {
            Log.w(TAG, "filterFeedChars fail-open", t);
            return data;
        }
    }

    /** Read an InputStream fully to a UTF-8 string. */
    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    /** Read a Reader fully to a string. */
    private static String readReader(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
        return sb.toString();
    }
}
