package com.feurstagram.patches.network

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val GATE_CLASS = "Lcom/feurstagram/extension/HashtagGate;"

/**
 * Instagram 449's JSON parser factories: methods returning the obfuscated
 * parser object (LX/03Yf;). Found by scanning all 22 DEX files.
 *
 * The feed is a large network response, so it likely arrives as InputStream
 * or byte[], NOT as a String. The v2 patch only hooked String factories,
 * which is why account JSON (logged_in_user, user_info) was caught but the
 * feed was missed.
 *
 * Each target specifies the param types so the patch can generate the right
 * Smali: String/Stream/bytes are single-register replacements; the [B I and
 * [C I variants need the offset register zeroed after filtering.
 */
private data class ParserFactoryTarget(
    val definingClass: String,
    val methodName: String,
    /** "string", "stream", "bytes", "bytesOff", "reader", "charsOff" */
    val kind: String,
    val paramTypes: List<String>,
)

private val PARSER_FACTORIES = listOf(
    // ---- String factories (v2, already working for account JSON) ----
    ParserFactoryTarget("LX/01yu;", "B3N", "string", listOf("Ljava/lang/String;")),
    ParserFactoryTarget("LX/01yv;", "A02", "string", listOf("Ljava/lang/String;")),
    ParserFactoryTarget("LX/02A1;", "A00", "string", listOf("Ljava/lang/String;")),
    ParserFactoryTarget("LX/02uu;", "B3N", "string", listOf("Ljava/lang/String;")),
    ParserFactoryTarget("LX/0FBT;", "B3N", "string", listOf("Ljava/lang/String;")),
    ParserFactoryTarget("LX/0Kfb;", "B3N", "string", listOf("Ljava/lang/String;")),
    ParserFactoryTarget("LX/0Pde;", "B3N", "string", listOf("Ljava/lang/String;")),
    ParserFactoryTarget("LX/08BW;", "A00", "string", listOf("LX/080E;", "Ljava/lang/String;")),
    // ---- InputStream factories (likely the feed path!) ----
    ParserFactoryTarget("LX/01yu;", "B3M", "stream", listOf("Ljava/io/InputStream;")),
    ParserFactoryTarget("LX/02uu;", "B3M", "stream", listOf("Ljava/io/InputStream;")),
    ParserFactoryTarget("LX/0FBT;", "B3M", "stream", listOf("Ljava/io/InputStream;")),
    ParserFactoryTarget("LX/0Kfb;", "B3M", "stream", listOf("Ljava/io/InputStream;")),
    ParserFactoryTarget("LX/0Pde;", "B3M", "stream", listOf("Ljava/io/InputStream;")),
    ParserFactoryTarget("LX/01yv;", "A0E", "stream", listOf("Ljava/io/InputStream;")),
    ParserFactoryTarget("LX/0brb;", "A0E", "stream", listOf("Ljava/io/InputStream;")),
    ParserFactoryTarget("LX/01yv;", "A0A", "stream", listOf("LX/01zo;", "Ljava/io/InputStream;")),
    ParserFactoryTarget("LX/0brb;", "A0A", "stream", listOf("LX/01zo;", "Ljava/io/InputStream;")),
    // ---- byte[] factories ----
    ParserFactoryTarget("LX/01yv;", "A0F", "bytes", listOf("[B")),
    ParserFactoryTarget("LX/0brb;", "A0F", "bytes", listOf("[B")),
    ParserFactoryTarget("LX/01yv;", "A0C", "bytesOff", listOf("LX/01zo;", "[B", "I")),
    ParserFactoryTarget("LX/0brb;", "A0C", "bytesOff", listOf("LX/01zo;", "[B", "I")),
    // ---- Reader factories ----
    ParserFactoryTarget("LX/01yv;", "A0B", "reader", listOf("LX/01zo;", "Ljava/io/Reader;")),
    ParserFactoryTarget("LX/0brb;", "A0B", "reader", listOf("LX/01zo;", "Ljava/io/Reader;")),
    // ---- char[] factories ----
    ParserFactoryTarget("LX/01yv;", "A0D", "charsOff", listOf("LX/01zo;", "[C", "I")),
    ParserFactoryTarget("LX/0brb;", "A0D", "charsOff", listOf("LX/01zo;", "[C", "I")),
)

/**
 * Drops feed items whose caption hashtags match none of the enabled categories.
 *
 * Design notes (read before touching):
 * - Hooks Instagram's JSON parser factories at method entry: the raw JSON
 *   is filtered BEFORE parsing, so the full caption text is available.
 * - v3 hooks ALL factory types (String, InputStream, byte[], Reader, char[]),
 *   not just String, because the feed likely arrives as a stream/bytes.
 * - HashtagGate.filterFeedJson is fail-open: returns input unchanged when the
 *   filter is disabled, when the payload is not feed-like, or on any error.
 * - The fast substring pre-check keeps overhead negligible for non-feed data.
 * - If a factory is renamed in a future IG version, its fingerprint is
 *   skipped; the patch fails only if NONE match.
 */
@Suppress("unused")
val hashtagFeedFilterPatch = bytecodePatch(
    name = "Hashtag feed filtering",
    description = "Drops feed items whose caption hashtags match none of the enabled " +
        "categories (Education, Tech). Hooks all JSON parser factories " +
        "(String/InputStream/byte[]/Reader) and filters the raw JSON before " +
        "parsing. Fail-open. Gated on the Hashtag filter toggle.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        var hooked = 0
        for (target in PARSER_FACTORIES) {
            try {
                val fingerprint = Fingerprint(
                    definingClass = target.definingClass,
                    name = target.methodName,
                    parameters = target.paramTypes,
                )
                fingerprint.method.apply {
                    val impl = implementation
                        ?: throw PatchException("Hashtag filter: no implementation for ${target.methodName}")
                    // Params occupy the last N registers: v[rc-N] .. v[rc-1].
                    val rc = impl.registerCount
                    val n = target.paramTypes.size
                    when (target.kind) {
                        "string" -> {
                            // Last register holds the String.
                            val r = rc - 1
                            addInstructions(
                                0,
                                "invoke-static/range { v$r .. v$r }, " +
                                    "$GATE_CLASS->filterFeedJson(Ljava/lang/String;)Ljava/lang/String;\n" +
                                    "move-result-object v$r",
                            )
                        }
                        "stream" -> {
                            // Last register holds the InputStream.
                            val r = rc - 1
                            addInstructions(
                                0,
                                "invoke-static/range { v$r .. v$r }, " +
                                    "$GATE_CLASS->filterFeedStream(Ljava/io/InputStream;)Ljava/io/InputStream;\n" +
                                    "move-result-object v$r",
                            )
                        }
                        "bytes" -> {
                            // Last register holds the byte[].
                            val r = rc - 1
                            addInstructions(
                                0,
                                "invoke-static/range { v$r .. v$r }, " +
                                    "$GATE_CLASS->filterFeedBytes([B)[B;\n" +
                                    "move-result-object v$r",
                            )
                        }
                        "bytesOff" -> {
                            // v[rc-2] = byte[], v[rc-1] = int offset.
                            // Filter returns a fresh array; zero the offset.
                            val rb = rc - 2
                            val ro = rc - 1
                            addInstructions(
                                0,
                                "invoke-static/range { v$rb .. v$ro }, " +
                                    "$GATE_CLASS->filterFeedBytesWithOffset([BI)[B;\n" +
                                    "move-result-object v$rb\n" +
                                    "const/4 v$ro, 0x0",
                            )
                        }
                        "reader" -> {
                            // Last register holds the Reader.
                            val r = rc - 1
                            addInstructions(
                                0,
                                "invoke-static/range { v$r .. v$r }, " +
                                    "$GATE_CLASS->filterFeedReader(Ljava/io/Reader;)Ljava/io/Reader;\n" +
                                    "move-result-object v$r",
                            )
                        }
                        "charsOff" -> {
                            // v[rc-2] = char[], v[rc-1] = int offset.
                            val rb = rc - 2
                            val ro = rc - 1
                            addInstructions(
                                0,
                                "invoke-static/range { v$rb .. v$ro }, " +
                                    "$GATE_CLASS->filterFeedChars([CI)[C;\n" +
                                    "move-result-object v$rb\n" +
                                    "const/4 v$ro, 0x0",
                            )
                        }
                    }
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
