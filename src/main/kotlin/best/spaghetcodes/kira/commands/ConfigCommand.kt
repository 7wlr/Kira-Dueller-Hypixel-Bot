package best.spaghetcodes.kira.commands

import best.spaghetcodes.kira.kira
import gg.essential.api.EssentialAPI
import gg.essential.api.commands.Command
import gg.essential.api.commands.DefaultHandler

class ConfigCommand : Command("kira") {

    @DefaultHandler
    fun handle() {
        // Utilise la nouvelle GUI custom au lieu de la GUI Vigilance par défaut
        EssentialAPI.getGuiUtil().openScreen(kira.config?.getCustomGui())
    }
}
