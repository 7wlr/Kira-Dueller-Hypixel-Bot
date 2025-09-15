package best.spaghetcodes.kira.utils

import best.spaghetcodes.kira.kira
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
        if (kira.mc.thePlayer != null) {
            kira.mc.thePlayer.sendChatMessage(message)
        }
    }

    fun info(message: String, force: Boolean = false) {
        // Prefixe chat changé : [KIRA] en bleu ciel (AQUA)
        sendChatMessage(
            "${EnumChatFormatting.AQUA}[${EnumChatFormatting.BOLD}KIRA${EnumChatFormatting.RESET}${EnumChatFormatting.AQUA}] ${EnumChatFormatting.WHITE}$message",
            force
        )
    }

    fun warn(message: String, force: Boolean = false) {
        // Même prefixe, message en jaune pour un avertissement
        sendChatMessage(
            "${EnumChatFormatting.AQUA}[${EnumChatFormatting.BOLD}KIRA${EnumChatFormatting.RESET}${EnumChatFormatting.AQUA}] ${EnumChatFormatting.YELLOW}$message",
            force
        )
    }

    fun error(message: String, force: Boolean = false) {
        // Même prefixe, message en rouge
        sendChatMessage(
            "${EnumChatFormatting.AQUA}[${EnumChatFormatting.BOLD}KIRA${EnumChatFormatting.RESET}${EnumChatFormatting.AQUA}] ${EnumChatFormatting.RED}$message",
            force
        )
    }

    private fun sendChatMessage(message: String, force: Boolean = false) {
        if (kira.mc.thePlayer != null && (force || kira.config?.disableChatMessages != true)) {
            kira.mc.thePlayer.addChatMessage(ChatComponentText(message))
        }
    }

}
