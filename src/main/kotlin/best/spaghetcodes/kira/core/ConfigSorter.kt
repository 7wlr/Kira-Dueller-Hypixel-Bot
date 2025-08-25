package best.spaghetcodes.kira.core

import gg.essential.vigilance.data.Category
import gg.essential.vigilance.data.SortingBehavior

class ConfigSorter : SortingBehavior() {

    private val items = arrayListOf(
        "General",
        "Combat",
        "Auto Requeue",
        "AutoGG",
        "Misc",
        "Queue Dodging"
        // "Webhook" retiré : la GUI custom ne l'affiche plus
    )

    override fun getCategoryComparator(): Comparator<in Category> = compareBy { items.indexOf(it.name) }
}
