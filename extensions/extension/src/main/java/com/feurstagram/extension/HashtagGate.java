package com.feurstagram.extension;

import android.util.Log;

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
 * Hashtag-based feed filter, invoked from the feed-item JSON parse hook
 * (see HashtagFeedFilterPatch).
 *
 * <p>Why this works despite Instagram hiding hashtags behind "more": the hook
 * runs at the JSON deserialisation layer, where the <b>full</b> caption text is
 * present. The collapsed caption is purely a UI rendering concern one layer up.
 *
 * <p>FAIL-OPEN BY DESIGN: any exception, any missing/unparseable caption, any
 * item with no classifiable hashtags returns the key unchanged, so the feed
 * renders exactly as Instagram intended. Nothing here can crash the app or
 * empty the feed.
 */
public final class HashtagGate {

    private HashtagGate() {}

    private static final String TAG = "FeurHashtag";

    /** Sink token shared with {@link Block}: the parser drops unknown types. */
    private static final String INVALID_FEED_TYPE = "feurstagram_blocked";

    private static final Pattern HASHTAG = Pattern.compile("#[\\p{L}\\p{N}_]+");
    private static final int MAX_JSON_CHARS = 1_000_000;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ARRAY_SCAN = 20;

    /**
     * Stashed by the patch immediately before each {@link #filterCurrentKey}
     * call (feed parsing is single-threaded per parse loop; worst case a stale
     * value fails open and the item is kept).
     */
    public static volatile Object lastItemJson;

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
     * Patch entry point. Returns the type token to continue parsing with, or
     * {@link #INVALID_FEED_TYPE} to drop the item. Never throws; never returns
     * null unless the input key was null.
     */
    public static String filterCurrentKey(String key) {
        if (key == null) return null;
        try {
            if (!Config.getBlocked("hashtag_filter_enabled", false)) return key;
            if (INVALID_FEED_TYPE.equals(key)) return key; // already dropped upstream

            String caption = captionOf(lastItemJson);
            if (caption == null || caption.isEmpty()) return key; // unclassifiable: fail open

            Set<String> tags = hashtagsIn(caption);
            if (tags.isEmpty()) return key; // no hashtags: fail open

            for (Map.Entry<String, Set<String>> e : CATEGORY_TAGS.entrySet()) {
                String prefKey = CATEGORY_PREF_KEYS.get(e.getKey());
                if (prefKey == null || !Config.getBlocked(prefKey, true)) continue;
                for (String t : tags) {
                    if (e.getValue().contains(t)) {
                        Log.i(TAG, "keep [" + key + "] tag=" + t);
                        return key;
                    }
                }
            }
            Log.i(TAG, "drop [" + key + "] tags=" + tags);
            return INVALID_FEED_TYPE;
        } catch (Throwable t) {
            Log.w(TAG, "fail-open", t);
            return key;
        }
    }

    /** Extract the caption text from the stashed item JSON, or null. */
    private static String captionOf(Object jsonInput) {
        String json = toJsonString(jsonInput);
        if (json == null) return null;
        try {
            return findCaptionText(new JSONObject(json), 0);
        } catch (Throwable t) {
            Log.w(TAG, "caption parse failed", t);
            return null;
        }
    }

    /** Normalise the hook's JSON parameter to a String. Null when unusable. */
    private static String toJsonString(Object o) {
        try {
            if (o instanceof String) {
                String s = (String) o;
                return s.length() > MAX_JSON_CHARS ? null : s;
            }
            if (o == null) return null;
            String s = o.toString(); // JSONObject / gson JsonObject / ...
            if (s == null || !s.startsWith("{")) {
                Log.w(TAG, "unexpected JSON param type: " + o.getClass().getName());
                return null;
            }
            return s.length() > MAX_JSON_CHARS ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Recursive search for {"caption": {"text": "..."}} (or "caption": "...").
     * Depth- and breadth-bounded so a pathological payload cannot hang the UI.
     */
    private static String findCaptionText(JSONObject obj, int depth) {
        if (obj == null || depth > MAX_DEPTH) return null;

        Object cap = obj.opt("caption");
        if (cap instanceof JSONObject) {
            String text = ((JSONObject) cap).optString("text", null);
            if (text != null && !text.isEmpty()) return text;
        } else if (cap instanceof String) {
            String s = (String) cap;
            if (!s.isEmpty()) return s;
        }

        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            Object v = obj.opt(keys.next());
            if (v instanceof JSONObject) {
                String r = findCaptionText((JSONObject) v, depth + 1);
                if (r != null) return r;
            } else if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                for (int i = 0, n = Math.min(a.length(), MAX_ARRAY_SCAN); i < n; i++) {
                    Object e = a.opt(i);
                    if (e instanceof JSONObject) {
                        String r = findCaptionText((JSONObject) e, depth + 1);
                        if (r != null) return r;
                    }
                }
            }
        }
        return null;
    }

    /** Lower-cased hashtags found in the caption. */
    private static Set<String> hashtagsIn(String caption) {
        Set<String> out = new HashSet<>();
        Matcher m = HASHTAG.matcher(caption);
        while (m.find()) out.add(m.group().toLowerCase(Locale.ROOT));
        return out;
    }
}
