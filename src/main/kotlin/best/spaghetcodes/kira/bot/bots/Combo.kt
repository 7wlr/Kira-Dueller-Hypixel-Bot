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

    // Potions de force : 2 doses à timings fixes (aucun check d’effet)
    private var strengthDosesUsed = 0
    override var lastPotion = 0L
    private var strengthCycleStarted = false
    private var nextStrengthAt = 0L
    private val strengthPeriodMs = 296_000L // 296s après le début de la 1ère dose

    // Gapples : cycle fixe 26s (on ne s’arrête jamais après 3)
    override var lastGap = 0L
    private var gapCycleStarted = false
    private var nextGapAt = 0L
    private val gapCyclePeriodMs = 26_000L

    // Perles (inchangé)
    private var pearls = 5
    private var lastPearl = 0L

    // Ouverture immédiate
    private var openingPhase = true
    private var openingScheduled = false

    // Verrou de consommation (rien d’autre pendant ~2s)
    private var consumingUntil = 0L
    private fun isConsuming(): Boolean = System.currentTimeMillis() < consumingUntil
    private fun setConsumeLock(ms: Int) {
        val now = System.currentTimeMillis()
        consumingUntil = now + ms + 150
        lockLeftAC = true
        Mouse.stopLeftAC()
        // Retour épée + réactivation AC juste après la fin nominale
        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            TimeUtils.setTimeout({
                lockLeftAC = false
            }, RandomUtils.randomIntInRange(140, 220))
        }, ms + 140)
    }

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

    // ------------------- Lifecycle -------------------
    override fun onGameStart() {
        Movement.startSprinting()
        Movement.startForward()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        openingPhase = true
        openingScheduled = false

        // Potions (2 doses planifiées par temps, aucun check d’effet)
        strengthDosesUsed = 0
        lastPotion = 0L
        strengthCycleStarted = false
        nextStrengthAt = 0L

        // Gapples (cycle temps fixe 26s)
        lastGap = 0L
        gapCycleStarted = false
        nextGapAt = 0L

        // Divers
        consumingUntil = 0L
        lastFarJumpAt = 0L
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        closeStrafeMode = MODE_BURST
        closeStrafeNextAt = 0L
        closeStrafeToggleAt = 0L

        lockLeftAC = false
        tapping = false

        pearls = 5
        lastPearl = 0L
    }

    override fun onGameEnd() {
        TimeUtils.setTimeout({
            Movement.clearAll()
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            tapping = false
            lockLeftAC = false

            // Reset cycles
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

    // ------------------- Ouverture (déterministe) -------------------
    private fun startOpening(distance: Float, player: net.minecraft.entity.player.EntityPlayer, target: net.minecraft.entity.Entity) {
        // 1) Potion immédiatement (dose #1)
        val holdMs = RandomUtils.randomIntInRange(1820, 1960)
        lastPotion = System.currentTimeMillis()
        strengthDosesUsed = 1
        strengthCycleStarted = true
        nextStrengthAt = lastPotion + strengthPeriodMs
        setConsumeLock(holdMs)
        // On délègue l’input réel au helper fiable
        usePotion(8, distance < 3, EntityUtils.entityFacingAway(target, player))

        // 2) Gap dès que la boisson est finie (légère marge)
        TimeUtils.setTimeout({
            val gapHold = RandomUtils.randomIntInRange(1820, 1960)
            lastGap = System.currentTimeMillis()
            gapCycleStarted = true
            nextGapAt = lastGap + gapCyclePeriodMs
            setConsumeLock(gapHold)
            useGap(distance, distance < 2, EntityUtils.entityFacingAway(player, target))
            openingPhase = false
        }, holdMs + RandomUtils.randomIntInRange(60, 120))
    }

    // ------------------- Tick principal -------------------
    override fun onTick() {
        val player = mc.thePlayer ?: return
        val target = opponent() ?: return

        val distance = EntityUtils.getDistanceNoY(player, target)
        val now = System.currentTimeMillis()

        // Toujours sprinter
        if (!player.isSprinting) Movement.startSprinting()

        // Tracking et left AC
        if (distance < 150) Mouse.startTracking() else Mouse.stopTracking()
        if (distance < 10) {
            if (player.heldItem != null && player.heldItem.unlocalizedName.lowercase().contains("sword")) {
                if (!lockLeftAC) Mouse.startLeftAC()
            }
        } else {
            Mouse.stopLeftAC()
        }

        // Sauts : > 8 blocs
        if (distance > 8 && player.onGround && now - lastFarJumpAt >= 540L) {
            Movement.singleJump(RandomUtils.randomIntInRange(130, 180))
            lastFarJumpAt = now
        }
        if (distance < 8) Movement.stopJumping()

        // Micro-hop anti-flat
        if (combo >= 3 && distance >= 3.2 && player.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
        }

        // Avance/arrêt simples
        if (distance < 1.5 || (distance < 2.4 && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // Pas de runaway si mur devant
        if (WorldUtils.blockInFront(player, 3f, 1.5f) != Blocks.air) {
            Mouse.setRunningAway(false)
        }

        // ---------- OUVERTURE IMMÉDIATE ----------
        if (openingPhase && !openingScheduled) {
            openingScheduled = true
            startOpening(distance, player, target)
        }

        // ---------- CYCLE POTION : dose #2 à +296s (aucun check d’effet) ----------
        if (!openingPhase && strengthCycleStarted && strengthDosesUsed == 1 && !isConsuming()) {
            if (now >= nextStrengthAt) {
                val hold = RandomUtils.randomIntInRange(1820, 1960)
                lastPotion = now
                strengthDosesUsed = 2 // dernière dose
                nextStrengthAt = 0L
                setConsumeLock(hold)
                usePotion(8, distance < 3, EntityUtils.entityFacingAway(target, player))
            }
        }

        // ---------- ARMURE simple ----------
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

        // ---------- CYCLE GAPPLE 26s (infini tant qu’on joue) ----------
        if (!openingPhase && gapCycleStarted && !isConsuming()) {
            if (now >= nextGapAt) {
                val hold = RandomUtils.randomIntInRange(1820, 1960)
                lastGap = now
                nextGapAt = lastGap + gapCyclePeriodMs // recale immédiatement (même si la sélection rate, on retentera au prochain tick>=now)
                setConsumeLock(hold)
                useGap(distance, distance < 2, EntityUtils.entityFacingAway(player, target))
            }
        }

        // ---------- Quick Pearl (inchangé, safe hors conso) ----------
        if (!isConsuming() &&
            distance > 18 &&
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

        // ---------- STRAFE “Classic-like” ----------
        val movePriority = arrayListOf(0, 0)
        val randomStrafe: Boolean

        if (distance < 8) {
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
            randomStrafe = true
        }

        handle(false, randomStrafe, movePriority)

        // Si plus en conso et lock AC encore actif, libère
        if (!isConsuming() && lockLeftAC) lockLeftAC = false
    }

    override fun onAttack() {
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 120)
    }
}
