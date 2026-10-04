package com.feurstagram.patches.network

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val GATE_CLASS = "Lcom/feurstagram/extension/HashtagGate;"

/**
 * Drops feed items whose caption hashtags match none of the enabled categories.
 *
 * Design notes (read before touching):
 * - Reuses FeedItemParseFromJsonFingerprint: the same proven parseFromJson
 *   anchor as the ad/suggested filter, so this patch lives or dies with that
 *   patch's fingerprint maintenance.
 * - The item JSON arrives as a method parameter. Its register is computed from
 *   the parameter list (last-N-registers layout), selecting the first parameter
 *   whose type looks like JSON. A streaming JsonReader is rejected loudly: it
 *   cannot be re-read here.
 * - The JSON is NOT passed as a second invoke argument (multi-register invoke
 *   is limited to 4-bit registers; parameter registers are high). Instead it is
 *   stashed in a static field with a single-register sput, then the existing
 *   single-register invoke pattern from FeedItemFilterPatch is reused for the
 *   keep/drop decision. Injection order vs. that patch does not matter: both
 *   orders compose correctly (an invalid token stays invalid through both).
 * - Fail-open: HashtagGate never throws and never returns null for a non-null
 *   key, so a misbehaving filter degrades to a normal feed, never a crash.
 */
@Suppress("unused")
val hashtagFeedFilterPatch = bytecodePatch(
    name = "Hashtag feed filtering",
    description = "Drops feed items whose caption hashtags match none of the enabled " +
        "categories (Education, Tech). Reads the item JSON at the parse layer, so " +
        "collapsed/hidden captions are no obstacle. Fail-open. Gated on the " +
        "Hashtag filter toggle (off by default).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        FeedItemParseFromJsonFingerprint.method.apply {
            // --- 1. Locate the item-JSON parameter register. ---
            val impl = implementation
                ?: throw PatchException("Hashtag filter: parseFromJson has no implementation")
            val paramTypes = parameters.map { it.type }
            val jsonIndex = paramTypes.indexOfFirst { t ->
                t.contains("json", ignoreCase = true) || t == "Ljava/lang/String;"
            }.takeIf { it >= 0 }
                ?: throw PatchException(
                    "Hashtag filter: no JSON/String parameter in parseFromJson " +
                        "(params: $paramTypes). Needs manual mapping for this Instagram version."
                )
            if (paramTypes[jsonIndex].contains("reader", ignoreCase = true)) {
                throw PatchException(
                    "Hashtag filter: item JSON arrives as a streaming reader, which cannot " +
                        "be re-read here. Needs manual mapping for this Instagram version."
                )
            }
            // Dalvik: parameters occupy the last registers; declared param i
            // (static or not) sits at v[registerCount - paramCount + i].
            val jsonRegister = impl.registerCount - paramTypes.size + jsonIndex

            // --- 2. Locate the type-token register (same structural search as
            // the ad/suggested filter: last move-result-object before the first
            // const-string/jumbo of the sparse-switch dispatch). ---
            val firstJumboIndex = instructions.first {
                it.opcode == Opcode.CONST_STRING_JUMBO
            }.location.index
            val keyLoad = instructions.last {
                it.opcode == Opcode.MOVE_RESULT_OBJECT && it.location.index < firstJumboIndex
            }
            val keyRegister = (keyLoad as OneRegisterInstruction).registerA

            // --- 3. Inject: stash JSON, then keep/drop on the key. ---
            addInstructions(
                keyLoad.location.index + 1,
                "sput-object v$jsonRegister, $GATE_CLASS->lastItemJson:Ljava/lang/Object;\n" +
                    "invoke-static/range { v$keyRegister .. v$keyRegister }, " +
                    "$GATE_CLASS->filterCurrentKey(Ljava/lang/String;)Ljava/lang/String;\n" +
                    "move-result-object v$keyRegister",
            )
        }
    }
}
