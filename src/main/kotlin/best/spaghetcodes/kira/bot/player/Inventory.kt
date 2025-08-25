package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.kira

object Inventory {

    /**
     * Sets the players current item to the item passed
     */
    fun setInvItem(item: String): Boolean {
        val _item = item.lowercase()
        if (kira.mc.thePlayer != null && kira.mc.thePlayer.inventory != null) {
            for (i in 0..8) {
                val stack = kira.mc.thePlayer.inventory.getStackInSlot(i)
                if (stack != null && stack.unlocalizedName.lowercase().contains(_item)) {
                    kira.mc.thePlayer.inventory.currentItem = i
                    return true
                }
            }
        }
        return false
    }

    /**
     * Set the current inventory item (by itemDamage, use for potions etc)
     */
    fun setInvItemByDamage(itemDamage: Int): Boolean {
        if (kira.mc.thePlayer != null && kira.mc.thePlayer.inventory != null) {
            for (i in 0..8) {
                val stack = kira.mc.thePlayer.inventory.getStackInSlot(i)
                if (stack != null && stack.itemDamage == itemDamage) {
                    kira.mc.thePlayer.inventory.currentItem = i
                    return true
                }
            }
        }
        return false
    }

    /**
     * Move the the passed inv slot
     */
    fun setInvSlot(slot: Int) {
        if (kira.mc.thePlayer != null && kira.mc.thePlayer.inventory != null) {
            kira.mc.thePlayer.inventory.currentItem = slot
        }
        // bruh
    }

    /**
     * Checks it the player has this item in their inventory
     */
    fun hasItem(item: String): Boolean {
        val _item = item.lowercase()
        if (kira.mc.thePlayer != null) {
            for (itemStack in kira.mc.thePlayer.getInventory()) {
                if (itemStack.unlocalizedName.lowercase().contains(_item)) {
                    return true
                }
            }
        }
        return false
    }

}