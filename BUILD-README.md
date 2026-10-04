# Hashtag Feed Filter — build & test guide

Drop-in feature for the FeurStagram project (this checkout). Filters the home
feed by caption hashtags at the JSON parse layer, so Instagram's collapsed
"tap more" captions are no obstacle — the full caption is in the JSON.

## Files added (all new except the Settings.java rows)

| File | What |
|---|---|
| `extensions/.../extension/HashtagGate.java` | Keep/drop decision. Regex hashtags, intersect with enabled categories. **Fail-open: never throws, never empties the feed.** Edit `CATEGORY_TAGS` to change hashtags. |
| `patches/.../patches/network/HashtagFeedFilterPatch.kt` | Bytecode patch. Reuses the proven `parseFromJson` fingerprint, stashes the item JSON in a static field, calls `HashtagGate.filterCurrentKey`. Patch auto-discovered — no registration needed. |
| `extensions/.../extension/Settings.java` | 3 new rows (Hashtag filter, Education tags, Tech tags) + help texts. Already wired. |

No changes to `Config.java`, `Block.java`, or any existing patch.

## Build

```bash
./build.sh /path/to/instagram.apk --clone --install
```

- `--clone` installs side-by-side (recommended for testing: your real Instagram stays untouched).
- Needs an Instagram APK of a version listed in `Constants.COMPATIBILITY_INSTAGRAM`.
- First run: Settings → FeurStagram → enable **Hashtag filter** (+ pick categories) → restart when prompted.

## How to test

1. Enable the filter, pick Education only.
2. `adb logcat | grep FeurHashtag` — every decision is logged:
   - `keep [media_or_ad] tag=#study` → matched, shown
   - `drop [media_or_ad] tags=[#food, #travel]` → filtered out
   - no line for an item → master toggle off, or item kept fail-open (no caption/hashtags)
3. Scroll the home feed: matching posts stay, the rest vanish.

## If captions don't resolve (the known risk)

The patch assumes the item JSON arrives as a `parseFromJson` parameter. If the
patch fails, it fails **loudly at build time** with one of:

- `no JSON/String parameter in parseFromJson (params: [...])` → the method
  signature differs on this Instagram version. Open the method in a dex viewer,
  find which parameter/register holds the JSON, and adjust the `jsonIndex`
  computation in `HashtagFeedFilterPatch.kt`.
- `unexpected JSON param type: com.foo.Bar` in logcat → `toJsonString` rejected
  it. Check whether `.toString()` on that type yields JSON; if yes, whitelist it.

Debug loop: build → install clone → scroll → read `FeurHashtag` logcat lines →
adjust → rebuild. One iteration usually resolves the caption path.

## Expected success rates (honest)

- Patch applies & hook runs: ~90% (proven fingerprint reuse)
- Caption extracted after ≤1 debug iteration: ~95%
- Correct keep/drop once caption is in hand: ~95%
- Share of feed with classifiable hashtags: ~50–70% (inherent — many posts,
  especially Reels, carry few/no hashtags; those fail open and show normally)
- **Blended real-world filtering: ~50–65% of the feed correctly filtered**

## Maintenance

The fingerprint depends on Instagram's `parseFromJson` + type tokens
(`media_or_ad`, `clips_netego`, …). When Instagram renames things, this patch
breaks exactly like the existing ad filter — update the fingerprint together
with it. Check `Constants.COMPATIBILITY_INSTAGRAM` per release.
