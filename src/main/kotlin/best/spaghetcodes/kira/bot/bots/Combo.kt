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
import net.minecraft.init.Items
import net.minecraft.util.Vec3
import kotlin.math.abs
import kotlin.math.max

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

    // --------- ouverture ----------
    private var openingPhase = true
    private var openingScheduled = false

    // --------- items & timestamps ----------
    private var strengthPots = 2
    override var lastPotion = 0L

    private var gaps = 32
    override var lastGap = 0L

    private var pearls = 5
    private var lastPearl = 0L

    private var tapping = false

    // --------- armure (simple) ----------
    enum class ArmorEnum { BOOTS, LEGGINGS, CHESTPLATE, HELMET }
    private var armor = hashMapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)

    // --------- strafes ----------
    private var prevDistance = -1f
    private var lastStrafeSwitch = 0L
    private var strafeDir = 1

    // close-range modes
    private var closeStrafeMode = 0
    private val MODE_BURST = 0
    private val MODE_HOLD_LEFT = 1
    private val MODE_HOLD_RIGHT = 2
    private var closeStrafeNextAt = 0L
    private var closeStrafeToggleAt = 0L

    // AD tap
    private var adTapActive = false
    private var adToggleAt = 0L

    // centre virtuel
    private var centerX = 0.0
    private var centerZ = 0.0

    // --------- verrou conso ----------
    private var consumingUntil = 0L
    private var consumingType = 0 // 0 none, 1 potion, 2 gap

    // --------- saut long range ----------
    private var lastFarJumpAt = 0L

    // --------- incoming hits ----------
    private var incomingStreak = 0
    private var lastHurtStamp = 0L
    private var prevHurtTime = 0

    // --------- quick pearl après gap ----------
    private var quickPearlQueued = false
    private var quickPearlAt = 0L
    private val quickPearlMinDist = 6.0f
    private val quickPearlMaxDist = 12.0f

    // --------- cycle gap 26s ----------
    private var gapCycleStarted = false
    private var nextGapAt = 0L
    private val gapCyclePeriodMs = 26_000L

    override fun onGameStart() {
        Movement.startSprinting()
        Movement.startForward()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        mc.thePlayer?.let {
            centerX = it.posX
            centerZ = it.posZ
        }

        openingPhase = true
        openingScheduled = false

        prevDistance = -1f
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        closeStrafeMode = MODE_BURST
        closeStrafeNextAt = 0L
        closeStrafeToggleAt = 0L
        adTapActive = false
        adToggleAt = 0L
        tapping = false

        consumingUntil = 0L
        consumingType = 0
        lastFarJumpAt = 0L

        incomingStreak = 0
        lastHurtStamp = 0L
        prevHurtTime = 0

        quickPearlQueued = false
        quickPearlAt = 0L

        gapCycleStarted = false
        nextGapAt = 0L

        strengthPots = max(0, strengthPots)
        gaps = max(0, gaps)
        pearls = max(0, pearls)
    }

    override fun onGameEnd() {
        TimeUtils.setTimeout({
            Movement.clearAll()
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            tapping = false

            strengthPots = 2
            lastPotion = 0L

            gaps = 32
            lastGap = 0L

            pearls = 5
            lastPearl = 0L

            armor = hashMapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)
            openingPhase = false
            openingScheduled = false
            Mouse.stopTracking()
            consumingUntil = 0L
            consumingType = 0

            incomingStreak = 0
            prevHurtTime = 0
            quickPearlQueued = false

            gapCycleStarted = false
            nextGapAt = 0L
        }, RandomUtils.randomIntInRange(100, 300))
    }

    // ---------- Helpers robustes : sélection -> pré-délai -> hold long -> validation ----------

    private fun holdMsLong(): Int = RandomUtils.randomIntInRange(1880, 1960)
    private fun preDelayMs(): Int = RandomUtils.randomIntInRange(80, 120)
    private fun postDelayMs(): Int = RandomUtils.randomIntInRange(120, 160)

    private fun isHoldingPotion(): Boolean {
        val it = mc.thePlayer?.heldItem ?: return false
        return it.item == Items.potionitem
    }

    private fun isHoldingGapple(): Boolean {
        val it = mc.thePlayer?.heldItem ?: return false
        return it.item == Items.golden_apple
    }

    // -- Sélection potion : alias + fallback slot 8 via usePotion(8, ...) (pour Hypixel Combo)
    private fun selectPotionReliably(distance: Float, oppFacingAway: Boolean): Boolean {
        if (Inventory.setInvItem("potion")) return true
        if (Inventory.setInvItem("strength")) return true
        if (Inventory.setInvItem("str")) return true
        // Fallback : on force la sélection via le helper de Potion (slot 8)
        // usePotion() va rClick brièvement, on remettra ensuite notre hold long par-dessus.
        usePotion(8, distance < 3f, oppFacingAway)
        return true
    }

    // --- Démarrer une boisson de force robuste (retourne true si pipeline lancé) ---
    private fun startDrinkStrengthPotion(distance: Float, oppFacingAway: Boolean, attempt: Int = 1): Boolean {
        val p = mc.thePlayer ?: return false
        if (p.isPotionActive(net.minecraft.potion.Potion.damageBoost) || strengthPots <= 0) return false

        val selected = selectPotionReliably(distance, oppFacingAway)
        if (!selected) return false

        val pre = preDelayMs()
        val hold = holdMsLong()
        val post = postDelayMs()
        val start = System.currentTimeMillis()

        consumingType = 1
        consumingUntil = start + pre + hold + post + 80

        TimeUtils.setTimeout({
            if (!isHoldingPotion()) {
                // une dernière tentative de s’assurer que la potion est bien en main
                selectPotionReliably(distance, oppFacingAway)
            }
            Mouse.rClick(hold)

            TimeUtils.setTimeout({
                val active = mc.thePlayer?.isPotionActive(net.minecraft.potion.Potion.damageBoost) ?: false
                if (active) {
                    strengthPots = max(0, strengthPots - 1)
                    lastPotion = System.currentTimeMillis()
                    consumingUntil = 0L
                    consumingType = 0
                } else {
                    consumingUntil = 0L
                    consumingType = 0
                    if (attempt == 1) {
                        // Retry complet unique
                        startDrinkStrengthPotion(distance, oppFacingAway, 2)
                    }
                }
            }, post)
        }, pre)

        return true
    }

    // --- Démarrer un manger de gapple robuste (retourne true si pipeline lancé) ---
    private fun startEatGapple(attempt: Int = 1): Boolean {
        if (gaps <= 0) return false
        val selected =
            Inventory.setInvItem("gap") ||
            Inventory.setInvItem("gapple") ||
            Inventory.setInvItem("apple") ||
            Inventory.setInvItem("golden_apple")
        if (!selected) return false

        val pre = preDelayMs()
        val hold = holdMsLong()
        val post = postDelayMs()
        val start = System.currentTimeMillis()

        consumingType = 2
        consumingUntil = start + pre + hold + post + 80

        TimeUtils.setTimeout({
            if (!isHoldingGapple()) {
                Inventory.setInvItem("gap") || Inventory.setInvItem("gapple") ||
                Inventory.setInvItem("apple") || Inventory.setInvItem("golden_apple")
            }
            Mouse.rClick(hold)

            TimeUtils.setTimeout({
                val active = mc.thePlayer?.isPotionActive(net.minecraft.potion.Potion.absorption) ?: false
                if (active) {
                    gaps = max(0, gaps - 1)
                    lastGap = System.currentTimeMillis()

                    if (!gapCycleStarted) gapCycleStarted = true
                    nextGapAt = lastGap + gapCyclePeriodMs

                    consumingUntil = 0L
                    consumingType = 0
                } else {
                    consumingUntil = 0L
                    consumingType = 0
                    if (attempt == 1) {
                        // Retry complet unique
                        startEatGapple(2)
                    }
                }
            }, post)
        }, pre)

        return true
    }

    // --- QuickPearl (après gap confirmée) -> perle puis potion ---
    private fun tryQuickPearlSequence(distance: Float) {
        if (!quickPearlQueued) return
        val now = System.currentTimeMillis()
        if (now < quickPearlAt || now < consumingUntil) return
        if (pearls <= 0) { quickPearlQueued = false; return }
        if (distance !in quickPearlMinDist..quickPearlMaxDist) { quickPearlQueued = false; return }
        if (now - lastPearl <= 5000) { quickPearlQueued = false; return }

        lastPearl = now
        val ok = Inventory.setInvItem("pearl")
        if (ok) {
            pearls = max(0, pearls - 1)
            Mouse.setUsingProjectile(true)
            TimeUtils.setTimeout({
                Mouse.rClick(RandomUtils.randomIntInRange(110, 160))
                TimeUtils.setTimeout({
                    Mouse.setUsingProjectile(false)
                    Inventory.setInvItem("sword")
                    TimeUtils.setTimeout({
                        if (!mc.thePlayer.isPotionActive(net.minecraft.potion.Potion.damageBoost)) {
                            startDrinkStrengthPotion(distance, EntityUtils.entityFacingAway(mc.thePlayer, opponent() ?: return@setTimeout))
                        }
                    }, RandomUtils.randomIntInRange(180, 260))
                }, RandomUtils.randomIntInRange(220, 300))
            }, RandomUtils.randomIntInRange(180, 260))
        }
        quickPearlQueued = false
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val consumingNow = now < consumingUntil

        // hits entrants (run-back)
        if (p.hurtTime > 0 && p.hurtTime > prevHurtTime) {
            incomingStreak++
            lastHurtStamp = now
        } else if (now - lastHurtStamp > 450) {
            incomingStreak = 0
        }
        prevHurtTime = p.hurtTime

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        // Sauts : > 8 blocs on saute périodiquement
        if (distance > 8f && p.onGround && now - lastFarJumpAt >= 540L) {
            Movement.singleJump(RandomUtils.randomIntInRange(130, 180))
            lastFarJumpAt = now
        }
        if (distance < 8f) Movement.stopJumping()

        // Micro-hop anti-flat
        if (combo >= 3 && distance >= 3.2f && p.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
        }

        // Avance/arrêt simples
        if (distance < 1.5f || (distance < 2.4f && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // pas de runaway si bloc devant
        if (WorldUtils.blockInFront(p, 3f, 1.5f) != Blocks.air) {
            Mouse.setRunningAway(false)
        }

        // ---------- OUVERTURE ----------
        if (openingPhase && !openingScheduled) {
            openingScheduled = true
            TimeUtils.setTimeout({
                val facingAway = EntityUtils.entityFacingAway(opp, p)
                startDrinkStrengthPotion(distance, facingAway)
                val waitMin = preDelayMs() + holdMsLong() + postDelayMs()
                TimeUtils.setTimeout({
                    if (!consumingNow && gaps > 0) {
                        val ok = startEatGapple()
                        if (ok && distance in quickPearlMinDist..quickPearlMaxDist && pearls > 0) {
                            quickPearlQueued = true
                            quickPearlAt = System.currentTimeMillis() + RandomUtils.randomIntInRange(220, 320)
                        }
                    }
                    TimeUtils.setTimeout({ openingPhase = false }, RandomUtils.randomIntInRange(120, 220))
                }, waitMin)
            }, RandomUtils.randomIntInRange(40, 80))
        }

        // ---------- POTION (hors ouverture) ----------
        if (!openingPhase &&
            !p.isPotionActive(net.minecraft.potion.Potion.damageBoost) &&
            now - lastPotion > 5000 &&
            !consumingNow
        ) {
            startDrinkStrengthPotion(distance, EntityUtils.entityFacingAway(opp, p))
        }

        // ---------- ARMURE (hors ouverture) ----------
        if (!openingPhase && !consumingNow) {
            for (i in 0..3) {
                if (p.inventory.armorItemInSlot(i) == null) {
                    if ((armor[i] ?: 0) > 0) {
                        TimeUtils.setTimeout({
                            if (System.currentTimeMillis() < consumingUntil) return@setTimeout
                            val ok = Inventory.setInvItem(ArmorEnum.values()[i].name.lowercase())
                            if (ok) {
                                armor[i] = (armor[i] ?: 0) - 1
                                TimeUtils.setTimeout({
                                    val r = RandomUtils.randomIntInRange(100, 150)
                                    Mouse.rClick(r)
                                    TimeUtils.setTimeout({
                                        Inventory.setInvItem("sword")
                                    }, r + RandomUtils.randomIntInRange(100, 150))
                                }, RandomUtils.randomIntInRange(200, 400))
                            }
                        }, RandomUtils.randomIntInRange(250, 500))
                    }
                }
            }
        }

        // ---------- CYCLE GAPPLE 26s ----------
        if (!openingPhase && gapCycleStarted && gaps > 0 && !consumingNow) {
            if (now >= nextGapAt) {
                val ok = startEatGapple()
                if (ok && distance in quickPearlMinDist..quickPearlMaxDist && pearls > 0) {
                    quickPearlQueued = true
                    quickPearlAt = System.currentTimeMillis() + RandomUtils.randomIntInRange(220, 320)
                }
                // nextGapAt est recalé uniquement quand l’Absorption est confirmée (dans startEatGapple)
            }
        }

        // ---------- QuickPearl planifiée ----------
        tryQuickPearlSequence(distance)

        // ---------- Run-back mid-combo ----------
        if (!openingPhase && !consumingNow && incomingStreak >= 6 && distance < 3.5f) {
            val dur = RandomUtils.randomIntInRange(280, 420)
            Movement.stopForward()
            Movement.startBackward()
            TimeUtils.setTimeout({
                Movement.stopBackward()
                Movement.startForward()
            }, dur)
            if (incomingStreak >= 8 && pearls > 0 && now - lastPearl > 5000) {
                TimeUtils.setTimeout({
                    if (System.currentTimeMillis() < consumingUntil) return@setTimeout
                    if (Inventory.setInvItem("pearl")) {
                        pearls = max(0, pearls - 1)
                        lastPearl = System.currentTimeMillis()
                        Mouse.setUsingProjectile(true)
                        TimeUtils.setTimeout({
                            Mouse.rClick(RandomUtils.randomIntInRange(100, 150))
                            TimeUtils.setTimeout({
                                Mouse.setUsingProjectile(false)
                                Inventory.setInvItem("sword")
                            }, RandomUtils.randomIntInRange(220, 300))
                        }, RandomUtils.randomIntInRange(220, 300))
                    }
                }, RandomUtils.randomIntInRange(60, 120))
            }
        }

        // ---------- STRAFE “Classic” + AD tap ----------
        val movePriority = arrayListOf(0, 0)
        var randomStrafe = false

        fun edgeAhead(distProbe: Float) =
            WorldUtils.blockInFront(p, distProbe, 0.0f) == Blocks.air

        val voidNear = edgeAhead(1.4f) || edgeAhead(2.0f)
        if (voidNear) {
            val toLeft = WorldUtils.leftOrRightToPoint(p, Vec3(centerX, 0.0, centerZ))
            val w = 10
            if (toLeft) movePriority[0] += w else movePriority[1] += w
            randomStrafe = false
        } else {
            if (distance < 2.6f) {
                if (now >= closeStrafeNextAt) {
                    val roll = RandomUtils.randomIntInRange(0, 99)
                    closeStrafeMode = when {
                        roll < 50 -> MODE_BURST
                        roll < 75 -> MODE_HOLD_LEFT
                        else     -> MODE_HOLD_RIGHT
                    }
                    closeStrafeNextAt = now + when (closeStrafeMode) {
                        MODE_BURST -> RandomUtils.randomIntInRange(280, 420).toLong()
                        else       -> RandomUtils.randomIntInRange(220, 340).toLong()
                    }
                    if (closeStrafeMode == MODE_BURST) {
                        closeStrafeToggleAt = now + RandomUtils.randomIntInRange(60, 110)
                    } else {
                        strafeDir = if (closeStrafeMode == MODE_HOLD_LEFT) -1 else 1
                    }
                } else if (closeStrafeMode == MODE_BURST && now >= closeStrafeToggleAt) {
                    strafeDir = -strafeDir
                    closeStrafeToggleAt = now + RandomUtils.randomIntInRange(60, 110)
                }

                val weightClose = 6
                if (strafeDir < 0) movePriority[0] += weightClose else movePriority[1] += weightClose
                Movement.startForward()
                Movement.startSprinting()
                randomStrafe = false
            } else {
                adTapActive = (combo in 3..12) && (distance in 2.4f..4.0f)
                if (adTapActive) {
                    if (now >= adToggleAt) {
                        strafeDir = -strafeDir
                        adToggleAt = now + RandomUtils.randomIntInRange(80, 120)
                    }
                } else if (distance < 6.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(820, 1100)) {
                    strafeDir = -strafeDir
                    lastStrafeSwitch = now
                }

                val deltaDist = if (prevDistance > 0f) abs(distance - prevDistance) else 999f
                if (distance in 1.8f..3.6f && deltaDist < 0.03f && now - lastStrafeSwitch > 260) {
                    strafeDir = -strafeDir
                    lastStrafeSwitch = now
                }
                val weight = if (distance < 4f) 7 else 5
                if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
                randomStrafe = (!adTapActive && distance in 8.0f..15.0f)
            }
        }

        handle(false, randomStrafe, movePriority)
        prevDistance = distance
    }

    override fun onAttack() {
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 120)
    }
}
