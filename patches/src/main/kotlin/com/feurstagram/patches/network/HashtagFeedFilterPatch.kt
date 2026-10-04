package com.feurstagram.patches.network

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val GATE_CLASS = "Lcom/feurstagram/extension/HashtagGate;"

/**
 * Instagram 449's JSON parser factories: methods taking a raw JSON String and
 * returning the obfuscated parser object (LX/03Yf;). Verified by disassembling
 * the 449 APK: the feed dispatcher (LX/04fY;->unsafeParseFromJson) consumes
 * parser objects produced by these factories.
 */
private data class ParserFactoryTarget(
    val definingClass: String,
    val methodName: String,
)

private val PARSER_FACTORIES = listOf(
    ParserFactoryTarget("LX/01yu;", "B3N"),
    ParserFactoryTarget("LX/01yv;", "A02"),
    ParserFactoryTarget("LX/02A1;", "A00"),
    ParserFactoryTarget("LX/02uu;", "B3N"),
    ParserFactoryTarget("LX/0FBT;", "B3N"),
    ParserFactoryTarget("LX/0Kfb;", "B3N"),
)

private val factoryFingerprints: List<Pair<ParserFactoryTarget, Fingerprint>> =
    PARSER_FACTORIES.map { target ->
        target to Fingerprint(
            definingClass = target.definingClass,
            name = target.methodName,
            parameters = listOf("Ljava/lang/String;"),
        )
    }

/**
 * Drops feed items whose caption hashtags match none of the enabled categories.
 *
 * Design notes (read before touching):
 * - Hooks Instagram's JSON parser factories at method entry: the raw JSON
 *   response String is filtered BEFORE parsing, so the full caption text is
 *   available as real JSON (no obfuscated parser API to navigate).
 * - HashtagGate.filterFeedJson is fail-open: it returns the input unchanged
 *   when the filter is disabled, when the payload is not a feed response, or
 *   on any parse error. It only REMOVES "media_or_ad" items from the "items"
 *   array; it never modifies item content.
 * - The fast substring pre-check ("media_or_ad") keeps overhead negligible for
 *   non-feed responses.
 * - If a factory method is renamed in a future Instagram version, its
 *   fingerprint is skipped and the rest still hook; the patch fails only if
 *   NONE match.
 */
@Suppress("unused")
val hashtagFeedFilterPatch = bytecodePatch(
    name = "Hashtag feed filtering",
    description = "Drops feed items whose caption hashtags match none of the enabled " +
        "categories (Education, Tech). Filters the raw JSON response before " +
        "parsing, so collapsed/hidden captions are no obstacle. Fail-open. " +
        "Gated on the Hashtag filter toggle (off by default).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        var hooked = 0
        for ((target, fingerprint) in factoryFingerprints) {
            try {
                fingerprint.method.apply {
                    // Single String param: it occupies the last register
                    // (v[registerCount-1]) for both static and instance methods.
                    val impl = implementation
                        ?: throw PatchException("Hashtag filter: no implementation for ${target.methodName}")
                    val stringRegister = impl.registerCount - 1
                    addInstructions(
                        0,
                        "invoke-static/range { v$stringRegister .. v$stringRegister }, " +
                            "$GATE_CLASS->filterFeedJson(Ljava/lang/String;)Ljava/lang/String;\n" +
                            "move-result-object v$stringRegister",
                    )
                    hooked++
                }
            } catch (e: Exception) {
                // Method renamed/removed in this Instagram version; the
                // remaining factories still cover JSON parsing.
            }
        }
        if (hooked == 0) {
            throw PatchException(
                "Hashtag filter: no JSON parser factory method found. " +
                    "Needs manual mapping for this Instagram version."
            )
        }
    }
}
