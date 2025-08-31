package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.features.Gap
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.features.Potion
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.EntityUtils
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.utils.WorldUtils
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3

class Combo : BotBase("/play duels_combo_duel"), MovePriority, Gap, Potion {

    override fun getName(): String = "Combo"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.combo_duel_wins",
                "losses" to "player.stats.Duels.combo_duel_losses",
                "ws" to "player.stats.Duels.current_combo_winstreak"
            )
        )
    }

    // ------------------- Etat & ressources -------------------
    private var tapping = false
    private var lockLeftAC = false

    // Potion (2 doses, timings fixes sans check d'effet)
    private var strengthDosesUsed = 0
    override var lastPotion = 0L
    private var strengthCycleStarted = false
    private var nextStrengthAt = 0L
    private val strengthPeriodMs = 296_000L // 296s après début dose #1

    // Gap cycle fixe (26s)
    override var lastGap = 0L
    private var gapCycleStarted = false
    private var nextGapAt = 0L
    private val gapPeriodMs = 26_000L

    // Perles
    private var pearls = 5
    private var lastPearl = 0L

    // Ouverture
    private var openingPhase = true
    private var openingScheduled = false

    // Fenêtre de conso (empêche toute interruption)
    private var consumingUntil = 0L
    private fun isConsuming(): Boolean = System.currentTimeMillis() < consumingUntil

    // Mouvements
    private var lastFarJumpAt = 0L
    private var lastStrafeSwitch = 0L
    private var strafeDir = 1
    private var closeStrafeMode = 0
    private val MODE_BURST = 0
    private val MODE_HOLD_LEFT = 1
    private val MODE_HOLD_RIGHT = 2
    private var closeStrafeNextAt = 0L
    private var closeStrafeToggleAt = 0L

    enum class ArmorEnum { BOOTS, LEGGINGS, CHESTPLATE, HELMET }
    private var armor = hashMapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)

    // ------------------- Utils inventaire / conso -------------------
    private fun equipAny(vararg keys: String): Boolean {
        for (k in keys) if (Inventory.setInvItem(k)) return true
        return false
    }

    /** Verrouille ~2.1s + marge, coupe l’auto-clic gauche, puis revient à l’épée si demandé. */
    private fun beginConsumeWindow(holdMs: Int, returnSword: Boolean) {
        val now = System.currentTimeMillis()
        consumingUntil = now + holdMs + 150
        lockLeftAC = true
        Mouse.stopLeftAC()
        TimeUtils.setTimeout({
            if (returnSword) {
                Inventory.setInvItem("sword")
                TimeUtils.setTimeout({
                    lockLeftAC = false
                }, RandomUtils.randomIntInRange(120, 200))
            } else {
                // on laisse la main sans forcer l'épée (utile pour chaîner la gap d'ouverture)
                lockLeftAC = false
            }
        }, holdMs + 140)
    }

    /** Boit une potion (clic maintenu garanti ~2.1s). Ne vérifie pas l’effet. */
    private fun drinkPotionStrong(returnSword: Boolean) {
        // Sélection : alias courants (Hypixel Combo: souvent slot 8)
        if (!equipAny("potion", "strength", "str")) {
            // fallback : helper si dispo (sélection côté helper + rClick court), puis on allonge notre hold par-dessus
            usePotion(8, false, false)
            // on tente de revenir sur l'item potion via alias au tick suivant si besoin
            TimeUtils.setTimeout({ equipAny("potion", "strength", "str") }, 40)
        }

        val hold = 2100 // sûr pour 32 ticks de boisson
        beginConsumeWindow(hold, returnSword)
        Mouse.rClick(hold)

        // Cycle potion (timé sur le début de cette conso)
        lastPotion = System.currentTimeMillis()
        strengthDosesUsed += 1
        if (!strengthCycleStarted) {
            strengthCycleStarted = true
            nextStrengthAt = lastPotion + strengthPeriodMs
        } else {
            nextStrengthAt = 0L // plus de dose après la 2e
        }
    }

    /** Mange une gapple (clic maintenu garanti ~2.1s). */
    private fun eatGapStrong(distance: Float, player: net.minecraft.entity.player.EntityPlayer, target: net.minecraft.entity.Entity) {
        if (!equipAny("gap", "gapple", "apple", "golden_apple")) {
            // fallback : helper (sélection + rClick court), puis on tient nous-mêmes
            useGap(distance, distance < 2f, EntityUtils.entityFacingAway(player, target))
            TimeUtils.setTimeout({ equipAny("gap", "gapple", "apple", "golden_apple") }, 40)
        }
        val hold = 2100
        beginConsumeWindow(hold, true)
        Mouse.rClick(hold)

        lastGap = System.currentTimeMillis()
        if (!gapCycleStarted) gapCycleStarted = true
        nextGapAt = lastGap + gapPeriodMs
    }

    // ------------------- Lifecycle -------------------
    override fun onGameStart() {
        Movement.startSprinting()
        Movement.startForward()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        openingPhase = true
        openingScheduled = false

        strengthDosesUsed = 0
        lastPotion = 0L
        strengthCycleStarted = false
        nextStrengthAt = 0L

        lastGap = 0L
        gapCycleStarted = false
        nextGapAt = 0L

        pearls = 5
        lastPearl = 0L

        consumingUntil = 0L
        lastFarJumpAt = 0L

        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        closeStrafeMode = MODE_BURST
        closeStrafeNextAt = 0L
        closeStrafeToggleAt = 0L

        lockLeftAC = false
        tapping = false
    }

    override fun onGameEnd() {
        TimeUtils.setTimeout({
            Movement.clearAll()
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            tapping = false
            lockLeftAC = false

            strengthDosesUsed = 0
            lastPotion = 0L
            strengthCycleStarted = false
            nextStrengthAt = 0L

            lastGap = 0L
            gapCycleStarted = false
            nextGapAt = 0L

            pearls = 5
            lastPearl = 0L

            armor = hashMapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)

            openingPhase = false
            openingScheduled = false
            consumingUntil = 0L
            Mouse.stopTracking()
        }, RandomUtils.randomIntInRange(100, 300))
    }

    // ------------------- Ouverture déterministe (potion -> gap) -------------------
    private fun startOpening(distance: Float, player: net.minecraft.entity.player.EntityPlayer, target: net.minecraft.entity.Entity) {
        // 1) Potion d’ouverture SANS retour auto à l’épée (pour ne pas couper la gap)
        drinkPotionStrong(returnSword = false)

        // 2) Gap juste après la fin de la boisson
        val delay = 2150 // un poil au-dessus du hold (2100) pour être sûr
        TimeUtils.setTimeout({
            // Si quelque chose prolonge encore (rare), on attend la libération puis on mange
            fun tryChain() {
                if (isConsuming()) {
                    TimeUtils.setTimeout({ tryChain() }, 40)
                } else {
                    eatGapStrong(EntityUtils.getDistanceNoY(player, target), player, target)
                    openingPhase = false
                }
            }
            tryChain()
        }, delay)
    }

    // ------------------- Tick principal -------------------
    override fun onTick() {
        val player = mc.thePlayer ?: return
        val target = opponent() ?: return

        val distance = EntityUtils.getDistanceNoY(player, target)
        val now = System.currentTimeMillis()

        // Toujours sprinter
        if (!player.isSprinting) Movement.startSprinting()

        // Tracking / auto-clic : jamais pendant une conso ou l’ouverture
        if (distance < 150) Mouse.startTracking() else Mouse.stopTracking()
        if (!isConsuming() && !openingPhase && distance < 10) {
            if (player.heldItem != null && player.heldItem.unlocalizedName.lowercase().contains("sword")) {
                if (!lockLeftAC) Mouse.startLeftAC()
            }
        } else {
            Mouse.stopLeftAC()
        }

        // Sauts > 8 blocs
        if (distance > 8f && player.onGround && now - lastFarJumpAt >= 540L) {
            Movement.singleJump(RandomUtils.randomIntInRange(130, 180))
            lastFarJumpAt = now
        }
        if (distance < 8f) Movement.stopJumping()

        // Micro-hop anti-flat
        if (combo >= 3 && distance >= 3.2f && player.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
        }

        // Avance/stop
        if (distance < 1.5f || (distance < 2.4f && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // Pas de runaway si mur
        if (WorldUtils.blockInFront(player, 3f, 1.5f) != Blocks.air) {
            Mouse.setRunningAway(false)
        }

        // Ouverture
        if (openingPhase && !openingScheduled) {
            openingScheduled = true
            startOpening(distance, player, target)
        }

        // Potion #2 à +296s (aucun check d'effet)
        if (!openingPhase && strengthCycleStarted && strengthDosesUsed == 1 && !isConsuming()) {
            if (now >= nextStrengthAt) {
                drinkPotionStrong(returnSword = true)
            }
        }

        // Armure simple (hors conso)
        if (!isConsuming()) {
            for (i in 0..3) {
                if (player.inventory.armorItemInSlot(i) == null) {
                    Mouse.stopLeftAC()
                    lockLeftAC = true
                    if ((armor[i] ?: 0) > 0) {
                        TimeUtils.setTimeout({
                            val a = Inventory.setInvItem(ArmorEnum.values()[i].name.lowercase())
                            if (a) {
                                armor[i] = (armor[i] ?: 1) - 1
                                TimeUtils.setTimeout({
                                    val r = RandomUtils.randomIntInRange(100, 150)
                                    Mouse.rClick(r)
                                    TimeUtils.setTimeout({
                                        Inventory.setInvItem("sword")
                                        TimeUtils.setTimeout({
                                            lockLeftAC = false
                                        }, RandomUtils.randomIntInRange(200, 300))
                                    }, r + RandomUtils.randomIntInRange(100, 150))
                                }, RandomUtils.randomIntInRange(200, 400))
                            } else {
                                lockLeftAC = false
                            }
                        }, RandomUtils.randomIntInRange(250, 500))
                    }
                }
            }
        }

        // Gap toutes les 26s (infini)
        if (!openingPhase && gapCycleStarted && !isConsuming()) {
            if (now >= nextGapAt) {
                eatGapStrong(distance, player, target)
            }
        }

        // Quick pearl (safe hors conso)
        if (!isConsuming() &&
            distance > 18f &&
            EntityUtils.entityFacingAway(target, player) &&
            !Mouse.isRunningAway() &&
            now - lastPearl > 5000 &&
            pearls > 0
        ) {
            lastPearl = now
            Mouse.stopLeftAC()
            lockLeftAC = true
            TimeUtils.setTimeout({
                if (Inventory.setInvItem("pearl")) {
                    pearls--
                    Mouse.setUsingProjectile(true)
                    TimeUtils.setTimeout({
                        Mouse.rClick(RandomUtils.randomIntInRange(100, 150))
                        TimeUtils.setTimeout({
                            Mouse.setUsingProjectile(false)
                            Inventory.setInvItem("sword")
                            TimeUtils.setTimeout({
                                lockLeftAC = false
                            }, RandomUtils.randomIntInRange(200, 300))
                        }, RandomUtils.randomIntInRange(250, 300))
                    }, RandomUtils.randomIntInRange(300, 600))
                } else {
                    lockLeftAC = false
                }
            }, RandomUtils.randomIntInRange(250, 500))
        }

        // Strafes “Classic-like”
        val movePriority = arrayListOf(0, 0)
        val randomStrafe: Boolean

        if (distance < 8f) {
            if (target.isInvisibleToPlayer(player)) {
                if (WorldUtils.leftOrRightToPoint(player, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
                randomStrafe = false
            } else {
                if (distance < 2.6f) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs >= closeStrafeNextAt) {
                        val roll = RandomUtils.randomIntInRange(0, 99)
                        closeStrafeMode = when {
                            roll < 50 -> MODE_BURST
                            roll < 75 -> MODE_HOLD_LEFT
                            else -> MODE_HOLD_RIGHT
                        }
                        closeStrafeNextAt = nowMs + when (closeStrafeMode) {
                            MODE_BURST -> RandomUtils.randomIntInRange(280, 420).toLong()
                            else -> RandomUtils.randomIntInRange(220, 340).toLong()
                        }
                        if (closeStrafeMode == MODE_BURST) {
                            closeStrafeToggleAt = nowMs + RandomUtils.randomIntInRange(60, 110)
                        } else {
                            strafeDir = if (closeStrafeMode == MODE_HOLD_LEFT) -1 else 1
                        }
                    } else if (closeStrafeMode == MODE_BURST && System.currentTimeMillis() >= closeStrafeToggleAt) {
                        strafeDir = -strafeDir
                        closeStrafeToggleAt = System.currentTimeMillis() + RandomUtils.randomIntInRange(60, 110)
                    }
                    val weightClose = 6
                    if (strafeDir < 0) movePriority[0] += weightClose else movePriority[1] += weightClose
                    randomStrafe = false
                } else {
                    if (distance < 4f && combo > 2) {
                        val rotations = EntityUtils.getRotations(target, player, false)
                        if (rotations != null) {
                            if (rotations[0] < 0) movePriority[1] += 5 else movePriority[0] += 5
                        }
                        randomStrafe = false
                    } else {
                        randomStrafe = true
                    }
                }
            }
        } else {
            randomStrafe = true
        }

        handle(false, randomStrafe, movePriority)
    }

    override fun onAttack() {
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 120)
    }
}
