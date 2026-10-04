package com.feurstagram.extension;

import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
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
        try {
            if (!Config.getBlocked("hashtag_filter_enabled", false)) return json;
            // Fast path: not a feed-like response.
            if (!json.contains("media_or_ad")) return json;

            JSONObject root = new JSONObject(json);
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
                    if (removed <= 5) Log.i(TAG, "drop [media_or_ad] tags=" + tags);
                }
            }
            if (removed > 0) {
                Log.i(TAG, "feed filter: removed " + removed + "/" + total + " items");
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
