package best.spaghetcodes.duckdueller.gui

import best.spaghetcodes.duckdueller.DuckDueller
import best.spaghetcodes.duckdueller.bot.Session
import best.spaghetcodes.duckdueller.utils.ChatUtils
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiTextField
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.max
import kotlin.math.min

class CustomConfigGUI : GuiScreen() {

    private var currentTab = 0
    // Onglets: PAS de Dodging, on ajoute Webhook
    private val tabNames = listOf("General", "Combat", "Webhook", "Stats")
    private var fadeIn = 0f

    // champs éditables
    private lateinit var apiKeyField: GuiTextField
    private lateinit var webhookField: GuiTextField

    // couleurs
    private val primaryColor = Color(0, 240, 255, 255).rgb  // cyan KIRA
    private val backgroundColor = Color(15, 15, 25, 240).rgb
    private val cardColor = Color(25, 25, 40, 200).rgb

    // zones cliquables simples
    private data class Rect(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)
    private val hotspots = mutableListOf<Rect>()

    override fun initGui() {
        super.initGui()
        fadeIn = 0f
        Keyboard.enableRepeatEvents(true)

        val centerX = width / 2

        apiKeyField = GuiTextField(0, fontRendererObj, centerX - 140, 100, 280, 20).apply {
            maxStringLength = 36
            text = DuckDueller.config?.apiKey ?: ""
        }
        webhookField = GuiTextField(1, fontRendererObj, centerX - 140, 100, 280, 20).apply {
            maxStringLength = 180
            text = DuckDueller.config?.webhookURL ?: ""
        }

        buttonList.clear()
        buttonList.add(GuiButton(100, width / 2 - 60, height - 30, 120, 20, "Save & Close"))
    }

    override fun onGuiClosed() {
        super.onGuiClosed()
        Keyboard.enableRepeatEvents(false)
    }

    override fun updateScreen() {
        super.updateScreen()
        apiKeyField.updateCursorCounter()
        webhookField.updateCursorCounter()
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        hotspots.clear()
        if (fadeIn < 1f) fadeIn = min(1f, fadeIn + 0.05f)

        drawGradientRect(
            0, 0, width, height,
            Color(10, 10, 20, (250 * fadeIn).toInt()).rgb,
            Color(30, 10, 50, (250 * fadeIn).toInt()).rgb
        )

        val containerX = 40
        val containerY = 40
        val containerW = width - 80
        val containerH = height - 80

        drawRect(containerX, containerY, containerX + containerW, containerY + containerH, backgroundColor)
        drawBorder(containerX, containerY, containerW, containerH, primaryColor)

        drawCenteredString(fontRendererObj, "§lKIRA CONFIG", width / 2, 15, primaryColor)

        drawTabs(containerX, containerY - 25, mouseX, mouseY)

        val contentX = containerX + 20
        val contentY = containerY + 20
        when (currentTab) {
            0 -> drawGeneralTab(contentX, contentY)
            1 -> drawCombatTab(contentX, contentY)
            2 -> drawWebhookTab(contentX, contentY)
            3 -> drawStatsTab(contentX, contentY)
        }

        val status = if (DuckDueller.bot?.toggled() == true) "§aONLINE" else "§cOFFLINE"
        drawString(fontRendererObj, "Status: $status", width - 80, height - 10, -1)
        drawString(fontRendererObj, "§7KIRA UI", 5, height - 10, Color.GRAY.rgb)

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    private fun drawBorder(x: Int, y: Int, w: Int, h: Int, color: Int) {
        drawHorizontalLine(x, x + w, y, color)
        drawHorizontalLine(x, x + w, y + h, color)
        drawVerticalLine(x, y, y + h, color)
        drawVerticalLine(x + w, y, y + h, color)
    }

    private fun addHotspot(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hotspots += Rect(x, y, x + w, y + h, onClick)
    }

    private fun drawButton(x: Int, y: Int, w: Int, h: Int, label: String, active: Boolean = true) {
        val bg = if (active) Color(40, 40, 60, 140).rgb else Color(60, 60, 60, 120).rgb
        drawRect(x, y, x + w, y + h, bg)
        drawCenteredString(fontRendererObj, label, x + w / 2, y + 6, if (active) -1 else Color(160,160,160).rgb)
    }

    private fun drawTabs(x: Int, y: Int, mouseX: Int, mouseY: Int) {
        var tabX = x
        tabNames.forEachIndexed { index, name ->
            val selected = index == currentTab
            val bg = if (selected) Color(0, 240, 255, 100).rgb else Color(40, 40, 60, 100).rgb
            drawRect(tabX, y, tabX + 100, y + 25, bg)
            drawCenteredString(fontRendererObj, name, tabX + 50, y + 8, if (selected) primaryColor else -1)
            addHotspot(tabX, y, 100, 25) { currentTab = index }
            tabX += 102
        }
    }

    private fun selector(label: String, x: Int, y: Int, get: () -> Int, set: (Int) -> Unit, options: List<String>) {
        val cur = min(max(0, get()), options.lastIndex)
        drawString(fontRendererObj, "$label:", x, y, -1)
        drawButton(x + 110, y - 4, 18, 18, "«")
        addHotspot(x + 110, y - 4, 18, 18) {
            val v = if (cur <= 0) options.lastIndex else cur - 1
            set(v)
        }
        drawRect(x + 132, y - 6, x + 282, y + 14, cardColor)
        drawCenteredString(fontRendererObj, options[cur], x + 207, y, primaryColor)
        drawButton(x + 286, y - 4, 18, 18, "»")
        addHotspot(x + 286, y - 4, 18, 18) {
            val v = if (cur >= options.lastIndex) 0 else cur + 1
            set(v)
        }
    }

    private fun toggle(label: String, x: Int, y: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val v = get()
        drawString(fontRendererObj, label, x, y, -1)
        val boxX = x + 260; val boxY = y - 2; val w = 60; val h = 18
        drawRect(boxX, boxY, boxX + w, boxY + h, if (v) Color(30, 90, 30, 200).rgb else Color(90, 30, 30, 200).rgb)
        drawCenteredString(fontRendererObj, if (v) "ON" else "OFF", boxX + w/2, boxY + 5, if (v) Color(0x55FF55).rgb else Color(0xFF5555).rgb)
        addHotspot(boxX, boxY, w, h) { set(!get()) }
    }

    private fun number(label: String, x: Int, y: Int, get: () -> Int, set: (Int) -> Unit, minV: Int, maxV: Int, step: Int = 1) {
        val v = get()
        drawString(fontRendererObj, "$label", x, y, -1)
        drawButton(x + 200, y - 4, 18, 18, "–")
        addHotspot(x + 200, y - 4, 18, 18) { set(max(minV, v - step)) }
        drawRect(x + 222, y - 6, x + 282, y + 14, cardColor)
        drawCenteredString(fontRendererObj, v.toString(), x + 252, y, primaryColor)
        drawButton(x + 286, y - 4, 18, 18, "+")
        addHotspot(x + 286, y - 4, 18, 18) { set(min(maxV, v + step)) }
    }

    private fun decimal(label: String, x: Int, y: Int, get: () -> Float, set: (Float) -> Unit, minV: Float, maxV: Float, step: Float = 0.1f) {
        val v = get()
        drawString(fontRendererObj, "$label", x, y, -1)
        drawButton(x + 200, y - 4, 18, 18, "–")
        addHotspot(x + 200, y - 4, 18, 18) { set(max(minV, (v - step))) }
        drawRect(x + 222, y - 6, x + 282, y + 14, cardColor)
        drawCenteredString(fontRendererObj, String.format("%.2f", v), x + 252, y, primaryColor)
        drawButton(x + 286, y - 4, 18, 18, "+")
        addHotspot(x + 286, y - 4, 18, 18) { set(min(maxV, (v + step))) }
    }

    private fun drawGeneralTab(x: Int, y: Int) {
        var yOff = y
        drawString(fontRendererObj, "§lGENERAL SETTINGS", x, yOff, primaryColor); yOff += 25

        val cfg = DuckDueller.config ?: return

        val botNames = listOf("Sumo", "Boxing", "Classic", "OP", "Combo")
        selector("Current Bot", x, yOff, { cfg.currentBot }, { v ->
            cfg.currentBot = v
            DuckDueller.config?.bots?.get(v)?.let { DuckDueller.swapBot(it) }
        }, botNames)
        yOff += 24

        drawString(fontRendererObj, "API Key:", x, yOff, -1); yOff += 15
        apiKeyField.xPosition = x
        apiKeyField.yPosition = yOff
        apiKeyField.drawTextBox()
        yOff += 26

        toggle("Lobby Movement", x, yOff, { cfg.lobbyMovement }, { cfg.lobbyMovement = it }); yOff += 20
        toggle("Fast Requeue", x, yOff, { cfg.fastRequeue }, { cfg.fastRequeue = it }); yOff += 20
        toggle("Paper Requeue", x, yOff, { cfg.paperRequeue }, { cfg.paperRequeue = it }); yOff += 20
        toggle("Disable Chat Messages", x, yOff, { cfg.disableChatMessages }, { cfg.disableChatMessages = it }); yOff += 24

        number("Disconnect After Games", x, yOff, { cfg.disconnectAfterGames }, { cfg.disconnectAfterGames = it }, 0, 10000, 10); yOff += 20
        number("Disconnect After Minutes", x, yOff, { cfg.disconnectAfterMinutes }, { cfg.disconnectAfterMinutes = it }, 0, 500, 5); yOff += 24
        number("Auto Requeue Delay (ms)", x, yOff, { cfg.autoRqDelay }, { cfg.autoRqDelay = it }, 500, 5000, 50); yOff += 20
        number("Requeue After No Game (s)", x, yOff, { cfg.rqNoGame }, { cfg.rqNoGame = it }, 15, 60, 1); yOff += 24

        toggle("Enable AutoGG", x, yOff, { cfg.sendAutoGG }, { cfg.sendAutoGG = it }); yOff += 20
        number("AutoGG Delay (ms)", x, yOff, { cfg.ggDelay }, { cfg.ggDelay = it }, 50, 1000, 50); yOff += 20
        drawString(fontRendererObj, "AutoGG Message: ${cfg.ggMessage}", x, yOff, -1); yOff += 20

        toggle("Game Start Message", x, yOff, { cfg.sendStartMessage }, { cfg.sendStartMessage = it }); yOff += 20
        number("Start Message Delay (ms)", x, yOff, { cfg.startMessageDelay }, { cfg.startMessageDelay = it }, 50, 1000, 50)
    }

    private fun drawCombatTab(x: Int, y: Int) {
        var yOff = y
        drawString(fontRendererObj, "§lCOMBAT SETTINGS", x, yOff, primaryColor); yOff += 25

        val cfg = DuckDueller.config ?: return

        number("Min CPS", x, yOff, { cfg.minCPS }, { cfg.minCPS = it }, 6, 15, 1); yOff += 20
        number("Max CPS", x, yOff, { cfg.maxCPS }, { cfg.maxCPS = it }, 9, 18, 1); yOff += 24

        number("Horizontal Look Speed", x, yOff, { cfg.lookSpeedHorizontal }, { cfg.lookSpeedHorizontal = it }, 1, 50, 1); yOff += 20
        number("Vertical Look Speed", x, yOff, { cfg.lookSpeedVertical }, { cfg.lookSpeedVertical = it }, 1, 50, 1); yOff += 20
        decimal("Look Randomization", x, yOff, { cfg.lookRand }, { cfg.lookRand = it }, 0f, 2f, 0.05f); yOff += 24

        number("Max Look Distance", x, yOff, { cfg.maxDistanceLook }, { cfg.maxDistanceLook = it }, 10, 200, 5); yOff += 20
        number("Max Attack Distance", x, yOff, { cfg.maxDistanceAttack }, { cfg.maxDistanceAttack = it }, 3, 6, 1)
    }

    private fun drawWebhookTab(x: Int, y: Int) {
        var yOff = y
        drawString(fontRendererObj, "§lWEBHOOK", x, yOff, primaryColor); yOff += 25

        val cfg = DuckDueller.config ?: return

        toggle("Send Webhook Messages", x, yOff, { cfg.sendWebhookMessages }, { cfg.sendWebhookMessages = it }); yOff += 20

        drawString(fontRendererObj, "Webhook URL:", x, yOff, -1); yOff += 15
        webhookField.xPosition = x
        webhookField.yPosition = yOff
        webhookField.drawTextBox()
        yOff += 26

        toggle("Send Queue Stats", x, yOff, { cfg.sendWebhookStats }, { cfg.sendWebhookStats = it }); yOff += 20
        toggle("Send Dodge Alerts", x, yOff, { cfg.sendWebhookDodge }, { cfg.sendWebhookDodge = it }); yOff += 20
        drawString(fontRendererObj, "Note: Dodging n’est pas dans cette GUI.", x, yOff, Color.GRAY.rgb)
    }

    private fun drawStatsTab(x: Int, y: Int) {
        var yOff = y
        drawString(fontRendererObj, "§lSESSION STATISTICS", x, yOff, primaryColor); yOff += 25

        val wins = Session.wins
        val losses = Session.losses
        val wlr = if (losses == 0) wins.toFloat() else wins.toFloat() / losses
        val total = wins + losses
        val winrate = if (total == 0) 0f else (wins.toFloat() / total) * 100f

        drawStatCard("WINS", x, yOff, wins.toString(), Color.GREEN.rgb)
        drawStatCard("LOSSES", x + 120, yOff, losses.toString(), Color.RED.rgb)
        drawStatCard("W/L RATIO", x + 240, yOff, String.format("%.2f", wlr), primaryColor)
        yOff += 60

        drawStatCard("WIN RATE", x, yOff, String.format("%.1f%%", winrate), Color.CYAN.rgb)
        drawStatCard("GAMES", x + 120, yOff, total.toString(), Color.YELLOW.rgb)

        if (Session.startTime > 0) {
            val minutes = max(0L, (System.currentTimeMillis() - Session.startTime) / 1000 / 60)
            drawStatCard("TIME", x + 240, yOff, "${minutes}m", Color.MAGENTA.rgb)
        }
    }

    private fun drawStatCard(title: String, x: Int, y: Int, value: String, color: Int) {
        drawRect(x, y, x + 100, y + 50, cardColor)
        drawString(fontRendererObj, title, x + 5, y + 5, Color.GRAY.rgb)
        GL11.glPushMatrix()
        GL11.glTranslatef((x + 50).toFloat(), (y + 25).toFloat(), 0f)
        GL11.glScalef(1.5f, 1.5f, 1f)
        drawCenteredString(fontRendererObj, value, 0, 0, color)
        GL11.glPopMatrix()
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        super.mouseClicked(mouseX, mouseY, mouseButton)
        hotspots.firstOrNull { mouseX in it.x1..it.x2 && mouseY in it.y1..it.y2 }?.onClick?.invoke()
        when (currentTab) {
            0 -> apiKeyField.mouseClicked(mouseX, mouseY, mouseButton)
            2 -> webhookField.mouseClicked(mouseX, mouseY, mouseButton)
        }
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            saveAndClose(); return
        }
        if (currentTab == 0) apiKeyField.textboxKeyTyped(typedChar, keyCode)
        if (currentTab == 2) webhookField.textboxKeyTyped(typedChar, keyCode)
        super.keyTyped(typedChar, keyCode)
    }

    override fun actionPerformed(button: GuiButton) {
        when (button.id) {
            100 -> saveAndClose()
        }
    }

    private fun saveAndClose() {
        DuckDueller.config?.apply {
            apiKey = apiKeyField.text
            webhookURL = webhookField.text
            writeData()
        }
        ChatUtils.info("Configuration saved!")
        mc.displayGuiScreen(null)
    }

    override fun doesGuiPauseGame(): Boolean = false
}
