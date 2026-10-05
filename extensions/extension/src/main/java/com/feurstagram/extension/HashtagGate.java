package com.feurstagram.extension;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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

    /** One-shot diagnostic flag: log the feed JSON structure once per process. */
    private static volatile boolean sStructureLogged;

    /** One-shot: confirm the hook is hit at all. */
    private static volatile boolean sHookHitLogged;

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
        // UNCONDITIONAL one-shot: confirm the hook is hit at all, and capture
        // what the JSON looks like (first 300 chars). This runs before any
        // fast-path checks so we see ALL traffic through the hooked factories.
        if (!sHookHitLogged) {
            sHookHitLogged = true;
            String snippet = json.length() > 300 ? json.substring(0, 300) : json;
            diagLog("diag: HOOK HIT! json snippet=[" + snippet + "]");
        }
        try {
            if (!Config.getBlocked("hashtag_filter_enabled", false)) return json;
            // Fast path: not a feed-like response.
            if (!json.contains("media_or_ad")) return json;

            JSONObject root = new JSONObject(json);

            // DIAGNOSTIC: log the actual top-level structure once, so we can
            // remap if the feed doesn't use the expected "items" array.
            if (!sStructureLogged) {
                sStructureLogged = true;
                StringBuilder keys = new StringBuilder();
                Iterator<String> it = root.keys();
                while (it.hasNext()) {
                    if (keys.length() > 0) keys.append(",");
                    keys.append(it.next());
                }
                diagLog("diag: top-level keys=[" + keys + "]");
                JSONArray items0 = root.optJSONArray("items");
                if (items0 != null && items0.length() > 0) {
                    JSONObject first = items0.optJSONObject(0);
                    if (first != null) {
                        StringBuilder k2 = new StringBuilder();
                        Iterator<String> it2 = first.keys();
                        while (it2.hasNext()) {
                            if (k2.length() > 0) k2.append(",");
                            k2.append(it2.next());
                        }
                        diagLog("diag: first item keys=[" + k2 + "]");
                    }
                } else {
                    diagLog("diag: no 'items' array found");
                }
            }

            JSONArray items = root.optJSONArray("items");
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
}
