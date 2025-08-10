package best.spaghetcodes.duckdueller.utils

import best.spaghetcodes.duckdueller.DuckDueller
import net.minecraft.util.ChatComponentText
import net.minecraft.util.EnumChatFormatting

object ChatUtils {

    fun removeFormatting(text: String): String{
        var t = ""
        var skip = false
        for (i in text.indices) {
            if (!skip) {
                if (text[i] == '§') {
                    skip = true
                } else {
                    t += text[i]
                }
            } else {
                skip = false
            }
        }
        return t
    }

    fun sendAsPlayer(message: String) {
        if (DuckDueller.mc.thePlayer != null) {
            DuckDueller.mc.thePlayer.sendChatMessage(message)
        }
    }

    fun info(message: String) {
        // Prefixe chat changé : [KIRA] en bleu ciel (AQUA)
        sendChatMessage("${EnumChatFormatting.AQUA}[${EnumChatFormatting.BOLD}KIRA${EnumChatFormatting.RESET}${EnumChatFormatting.AQUA}] ${EnumChatFormatting.WHITE}$message")
    }

    fun error(message: String) {
        // Même prefixe, message en rouge
        sendChatMessage("${EnumChatFormatting.AQUA}[${EnumChatFormatting.BOLD}KIRA${EnumChatFormatting.RESET}${EnumChatFormatting.AQUA}] ${EnumChatFormatting.RED}$message")
    }

    private fun sendChatMessage(message: String) {
        if (DuckDueller.mc.thePlayer != null && DuckDueller.config?.disableChatMessages != true) {
            DuckDueller.mc.thePlayer.addChatMessage(ChatComponentText(message))
        }
    }

}
