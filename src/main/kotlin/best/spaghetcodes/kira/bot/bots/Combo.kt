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
                "ws" to "player.stats.Duels.current_combo_winstreak",
            )
        )
    }

    // ------------------- Etat & ressources -------------------
    private var tapping = false
    private var dontStartLeftAC = false

    private var strengthPots = 2
    override var lastPotion = 0L
    private var strengthCycleStarted = false
    private var nextStrengthAt = 0L
    private val strengthPeriodMs = 296_000L // 296s exactement, pas de check d’effet

    private var gaps = 32
    override var lastGap = 0L
    private var gapCycleStarted = false
    private var nextGapAt = 0L
    private val gapCyclePeriodMs = 26_000L

    private var pearls = 5
    private var lastPearl = 0L

    // Ouverture immédiate
    private var openingPhase = true
    private var openingScheduled = false

    // Verrou de consommation (rien d’autre pendant un hold)
    private var consumingUntil = 0L

    // Sauts long range / micro-hop
    private var lastFarJumpAt = 0L

    // Strafes “façon Classic”
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

    // ------------------- Helpers conso “forcés” -------------------
    private fun equipAny(vararg keys: String): Boolean {
        for (k in keys) if (Inventory.setInvItem(k)) return true
        return false
    }

    private fun isConsuming(): Boolean = System.currentTimeMillis() < consumingUntil

    /** Boit une potion **sans** vérifier l’effet. Programme la suivante à +296s s’il en reste. */
    private fun forceDrinkNow(): Boolean {
        if (strengthPots <= 0) return false
        if (!equipAny("potion", "strength", "str")) return false

        val hold = RandomUtils.randomIntInRange(1720, 1860) // ~32 ticks + petite marge
        val now = System.currentTimeMillis()
        consumingUntil = now + hold + 120
        dontStartLeftAC = true
        Mouse.stopLeftAC()
        Mouse.rClick(hold)

        // Décrément + timestamp
        strengthPots--
        lastPotion = now

        // Démarre/calage du cycle potion à +296s **depuis le début** de cette conso
        strengthCycleStarted = true
        nextStrengthAt = if (strengthPots > 0) lastPotion + strengthPeriodMs else 0L

        // Retour épée + réactivation AC
        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            TimeUtils.setTimeout({
                dontStartLeftAC = false
            }, RandomUtils.randomIntInRange(140, 220))
        }, hold + 140)

        return true
    }

    /** Mange une gapple et relance le cycle fixe à +26s. */
    private fun forceEatGapNow(): Boolean {
        if (gaps <= 0) return false
        if (!equipAny("gap", "gapple", "apple", "golden_apple")) return false

        val hold = RandomUtils.randomIntInRange(1720, 1860)
        val now = System.currentTimeMillis()
        consumingUntil = now + hold + 120
        dontStartLeftAC = true
        Mouse.stopLeftAC()
        Mouse.rClick(hold)

        gaps--
        lastGap = now
        gapCycleStarted = true
        nextGapAt = lastGap + gapCyclePeriodMs

        // Retour épée + réactivation AC
        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            TimeUtils.setTimeout({
                dontStartLeftAC = false
            }, RandomUtils.randomIntInRange(140, 220))
        }, hold + 140)

        return true
    }

    // ------------------- Lifecycle -------------------
    override fun onGameStart() {
        Movement.startSprinting()
        Movement.startForward()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        openingPhase = true
        openingScheduled = false

        strengthCycleStarted = false
        nextStrengthAt = 0L

        gapCycleStarted = false
        nextGapAt = 0L

        consumingUntil = 0L
        lastFarJumpAt = 0L

        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        closeStrafeMode = MODE_BURST
        closeStrafeNextAt = 0L
        closeStrafeToggleAt = 0L

        dontStartLeftAC = false
        tapping = false

        // Inventaire par défaut
        // strengthPots, gaps, pearls sont remis au onGameEnd()
    }

    override fun onGameEnd() {
        TimeUtils.setTimeout({
            Movement.clearAll()
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            tapping = false
            dontStartLeftAC = false

            strengthPots = 2
            lastPotion = 0L
            strengthCycleStarted = false
            nextStrengthAt = 0L

            gaps = 32
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

    // ------------------- Tick principal -------------------
    override fun onTick() {
        val player = mc.thePlayer ?: return
        val target = opponent() ?: return

        val distance = EntityUtils.getDistanceNoY(player, target)
        val now = System.currentTimeMillis()

        // Toujours sprinter
        if (!player.isSprinting) Movement.startSprinting()

        // Tracking et AC
        if (distance < 150) Mouse.startTracking() else Mouse.stopTracking()
        if (distance < 10) {
            if (player.heldItem != null && player.heldItem.unlocalizedName.lowercase().contains("sword")) {
                if (!dontStartLeftAC) Mouse.startLeftAC()
            }
        } else {
            Mouse.stopLeftAC()
        }

        // Sauts > 8 blocs
        if (distance > 8 && player.onGround && now - lastFarJumpAt >= 540L) {
            Movement.singleJump(RandomUtils.randomIntInRange(130, 180))
            lastFarJumpAt = now
        }
        // Anti-saut en mêlée
        if (distance < 8) Movement.stopJumping()

        // Micro-hop anti-flat (comme Classic)
        if (combo >= 3 && distance >= 3.2 && player.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
        }

        // Avance/arrêt simples (éviter de pousser quand collé)
        if (distance < 1.5 || (distance < 2.4 && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // Mur devant : pas de runaway
        if (WorldUtils.blockInFront(player, 3f, 1.5f) != Blocks.air) {
            Mouse.setRunningAway(false)
        }

        // ------------------- OUVERTURE IMMÉDIATE -------------------
        if (openingPhase && !openingScheduled) {
            openingScheduled = true
            // 1) Potion tout de suite (si dispo)
            val drank = forceDrinkNow()
            // 2) Puis gap dès que la boisson est finie (ou quasi immédiat si pas de potion)
            val delayToGap = if (drank) RandomUtils.randomIntInRange(1820, 1960) else RandomUtils.randomIntInRange(40, 80)
            TimeUtils.setTimeout({
                forceEatGapNow()
                openingPhase = false
            }, delayToGap)
        }

        // ------------------- CYCLE POTION à 296s (sans check d’effet) -------------------
        if (!openingPhase && strengthCycleStarted && strengthPots > 0 && !isConsuming()) {
            if (now >= nextStrengthAt) {
                // reboit et recale (s’il en restait justement une)
                val ok = forceDrinkNow()
                if (!ok) {
                    // si l’équipement rate, on retente un peu plus tard
                    nextStrengthAt = now + 600
                }
            }
        }

        // ------------------- ARMURE simple -------------------
        if (!isConsuming()) {
            for (i in 0..3) {
                if (player.inventory.armorItemInSlot(i) == null) {
                    Mouse.stopLeftAC()
                    dontStartLeftAC = true
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
                                            dontStartLeftAC = false
                                        }, RandomUtils.randomIntInRange(200, 300))
                                    }, r + RandomUtils.randomIntInRange(100, 150))
                                }, RandomUtils.randomIntInRange(200, 400))
                            } else {
                                dontStartLeftAC = false
                            }
                        }, RandomUtils.randomIntInRange(250, 500))
                    }
                }
            }
        }

        // ------------------- CYCLE GAPPLE 26s -------------------
        if (!openingPhase && gapCycleStarted && gaps > 0 && !isConsuming()) {
            if (now >= nextGapAt) {
                val ok = forceEatGapNow()
                if (!ok) nextGapAt = now + 600 // réessaie plus tard si l’équipement rate
            }
        }

        // ------------------- Quick Pearl (comme base) -------------------
        if (!isConsuming() &&
            distance > 18 &&
            EntityUtils.entityFacingAway(target, player) &&
            !Mouse.isRunningAway() &&
            now - lastPearl > 5000 &&
            pearls > 0
        ) {
            lastPearl = now
            Mouse.stopLeftAC()
            dontStartLeftAC = true
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
                                dontStartLeftAC = false
                            }, RandomUtils.randomIntInRange(200, 300))
                        }, RandomUtils.randomIntInRange(250, 300))
                    }, RandomUtils.randomIntInRange(300, 600))
                } else {
                    dontStartLeftAC = false
                }
            }, RandomUtils.randomIntInRange(250, 500))
        }

        // ------------------- STRAFE “Classic-like” -------------------
        val movePriority = arrayListOf(0, 0)
        val randomStrafe: Boolean

        if (distance < 8) {
            // En visibilité réduite, se décaler vers le centre (approche “safe”)
            if (target.isInvisibleToPlayer(player)) {
                if (WorldUtils.leftOrRightToPoint(player, Vec3(0.0, 0.0, 0.0))) {
                    movePriority[0] += 4
                } else {
                    movePriority[1] += 4
                }
                randomStrafe = false
            } else {
                // Close-range : pattern burst / holds (alternance rapide)
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
                    // 2.6–8 blocs : strafe guidé par la tête de l’ennemi
                    if (distance < 4 && combo > 2) {
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
            // Loin : strafe random léger, l’important est la vitesse + sauts périodiques
            randomStrafe = true
        }

        handle(false, randomStrafe, movePriority)

        // Si plus en conso et flag lock AC, libérer
        if (!isConsuming() && dontStartLeftAC) {
            dontStartLeftAC = false
        }
    }

    override fun onAttack() {
        // W-tap léger pour garder un avantage de KB
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 120)
    }
}
