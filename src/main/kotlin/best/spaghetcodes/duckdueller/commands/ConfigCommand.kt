package best.spaghetcodes.duckdueller.commands

import best.spaghetcodes.duckdueller.DuckDueller
import gg.essential.api.EssentialAPI
import gg.essential.api.commands.Command
import gg.essential.api.commands.DefaultHandler

class ConfigCommand : Command("duckdueller") {

    @DefaultHandler
    fun handle() {
        // Utilise la nouvelle GUI custom au lieu de la GUI Vigilance par défaut
        EssentialAPI.getGuiUtil().openScreen(DuckDueller.config?.getCustomGui())
    }
}
