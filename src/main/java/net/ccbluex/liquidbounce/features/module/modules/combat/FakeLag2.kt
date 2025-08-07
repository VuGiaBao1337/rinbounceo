/*
 * RinBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/rattermc/rinbounce69
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.modules.player.Blink
import net.ccbluex.liquidbounce.utils.PacketUtils
import net.ccbluex.liquidbounce.utils.extensions.*
import net.ccbluex.liquidbounce.utils.misc.StringUtils
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.network.Packet
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

object FakeLag : Module("FakeLag", Category.COMBAT, gameDetecting = false) {

    private val mode by mode("Mode", "Dynamic", arrayOf("Dynamic", "Always"))
    private val inboundPackets by boolean("InboundPackets", true)
    private val outboundPackets by boolean("OutboundPackets", true)
    private val ignoreTeammates by boolean("IgnoreTeammates", true)
    private val stopOnHurt by boolean("StopOnHurt", true)
    private val stopOnHurtTime by integer("StopOnHurtTime", 500, 0, 1000)
    private val startRange by float("StartRange", 6.0f, 3.0f, 10.0f)
    private val stopRange by float("StopRange", 3.5f, 1.0f, 6.0f)
    private val maxTargetRange by float("MaxTargetRange", 15.0f, 6.0f, 20.0f)
    private val delay by integer("Delay", 200, 25, 1000)
    private val maxBufferSize by integer("MaxBuffer", 2000, 100, 5000)
    private val flushThreshold by integer("FlushThreshold", 800, 100, 2000)
    private val debug by boolean("Debug", false)

    private val inboundQueue = ConcurrentLinkedQueue<TimedPacket>()
    private val outboundQueue = ConcurrentLinkedQueue<TimedPacket>()

    private var target: EntityPlayer? = null
    private var lastDisableTime = -1L
    private var lastHurt = false
    private var lastStartBlinkTime = -1L
    private val processTimer = MSTimer()

    private inner class Cold(private var lastMs: Long = 0) {
        private val creationTime = System.currentTimeMillis()
        private var time: Long = 0
        private var checkedFinish = false

        constructor() : this(0) {
            lastMs = System.currentTimeMillis()
        }

        fun start() {
            reset()
            checkedFinish = false
        }

        fun firstFinish(): Boolean {
            return checkAndSetFinish { System.currentTimeMillis() >= (time + lastMs) }
        }

        fun setCooldown(time: Long) {
            this.lastMs = time
        }

        fun hasFinished(): Boolean {
            return isElapsed(time + lastMs) { System.currentTimeMillis() }
        }

        fun isElapsed(delayMs: Long): Boolean {
            return System.currentTimeMillis() > creationTime + delayMs
        }

        fun getElapsedTime(): Long {
            return System.currentTimeMillis() - creationTime
        }

        fun finished(delay: Long): Boolean {
            return isElapsed(time) { System.currentTimeMillis() - delay }
        }

        fun isDelayComplete(l: Long): Boolean {
            return isElapsed(lastMs) { System.currentTimeMillis() - l }
        }

        fun reached(currentTime: Long): Boolean {
            return isElapsed(time) { Math.max(0L, System.currentTimeMillis() - currentTime) }
        }

        fun reset() {
            this.time = System.currentTimeMillis()
        }

        fun getTime(): Long {
            return Math.max(0L, System.currentTimeMillis() - time)
        }

        fun hasTimeElapsed(owo: Long, reset: Boolean): Boolean {
            if (getTime() >= owo) {
                if (reset) {
                    reset()
                }
                return true
            }
            return false
        }

        private fun checkAndSetFinish(condition: () -> Boolean): Boolean {
            if (condition() && !checkedFinish) {
                checkedFinish = true
                return true
            }
            return false
        }

        private fun isElapsed(targetTime: Long, currentTimeSupplier: () -> Long): Boolean {
            return currentTimeSupplier() >= targetTime
        }
    }

    private inner class TimedPacket(val packet: Packet<*>, val timestamp: Long) {
        val cold = Cold()

        constructor(packet: Packet<*>) : this(packet, System.currentTimeMillis())

        fun isElapsed(delayMs: Long, currentTime: Long): Boolean {
            return currentTime > timestamp + delayMs
        }
    }

    override fun onDisable() {
        flushAllPackets()
        target = null
    }

    override fun onEnable() {
        lastDisableTime = -1
        lastHurt = false
        lastStartBlinkTime = -1
        inboundQueue.clear()
        outboundQueue.clear()
        processTimer.reset()
    }

    val onPacket = handler<PacketEvent> { event ->
        if (!state) return@handler
        val packet = event.packet
        val player = mc.thePlayer ?: return@handler

        when {
            event.eventType == EventState.SEND && outboundPackets -> {
                event.cancelEvent()
                if (outboundQueue.size < maxBufferSize) {
                    outboundQueue.add(TimedPacket(packet, System.currentTimeMillis()))
                } else {
                    flushSomePackets(outboundQueue, flushThreshold)
                    outboundQueue.add(TimedPacket(packet, System.currentTimeMillis()))
                }
            }
            
            event.eventType == EventState.RECEIVE && inboundPackets -> {
                event.cancelEvent()
                if (inboundQueue.size < maxBufferSize) {
                    inboundQueue.add(TimedPacket(packet, System.currentTimeMillis()))
                } else {
                    flushSomePackets(inboundQueue, flushThreshold)
                    inboundQueue.add(TimedPacket(packet, System.currentTimeMillis()))
                }
            }
            
            else -> return@handler
        }
    }

    val onAttack = handler<AttackEvent> { event ->
        val targetEntity = event.targetEntity
        if (targetEntity is EntityPlayer) {
            if (ignoreTeammates && targetEntity.isTeammate(mc.thePlayer)) return@handler
            target = targetEntity
            if (debug) {
                debugMessage("New target: ${targetEntity.name}")
            }
        }
    }

    val onWorld = handler<WorldEvent> { 
        if (it.worldClient == null) flushAllPackets()
    }

    val onGameLoop = handler<GameLoopEvent> {
        if (!state) return@handler
        val player = mc.thePlayer ?: return@handler

        if (processTimer.hasTimePassed(50L)) {
            processQueues()
            processTimer.reset()
        }

        if (mode == "Always") {
            if (Blink.state) return@handler
            Blink.enable()
            return@handler
        }

        if (stopOnHurt && player.hurtTime > 0 && !lastHurt) {
            if (debug) debugMessage("Stop lag: hurt.")
            disableBlink()
        }
        lastHurt = player.hurtTime > 0

        handleTargetLogic(player)
    }

    private fun handleTargetLogic(player: EntityPlayerSP) {
        target?.let { target ->
            val distance = player.getDistanceToEntity(target).toFloat()

            when {
                distance < stopRange -> {
                    if (debug) debugMessage("Stop lag: too close.")
                    disableBlink()
                }
                
                distance > startRange -> {
                    if (debug) debugMessage("Stop lag: out of range.")
                    disableBlink()
                }
                
                distance > maxTargetRange -> {
                    if (debug) debugMessage("Release target: ${target.name}")
                    this.target = null
                    disableBlink()
                }

                distance in stopRange..startRange -> {
                    if (!Blink.state) {
                        if (debug) debugMessage("Start lag: in range.")
                        enableBlink()
                    }
                }
            }
        } ?: run {
            if (Blink.state) {
                disableBlink()
            }
        }
    }

    private fun processQueues() {
        val currentTime = System.currentTimeMillis()
        val delayTime = delay.toLong()

        processQueue(inboundQueue, currentTime, delayTime)
        processQueue(outboundQueue, currentTime, delayTime)
    }

    private fun processQueue(queue: Queue<TimedPacket>, currentTime: Long, delayTime: Long) {
        val maxProcess = min(queue.size, (queue.size * 0.3).toInt())
        var processed = 0
        
        while (processed < maxProcess && queue.isNotEmpty()) {
            val timedPacket = queue.peek()
            if (currentTime > timedPacket.timestamp + delayTime) {
                PacketUtils.sendPacketNoEvent(queue.poll().packet)
                processed++
            } else {
                break
            }
        }
    }

    private fun flushSomePackets(queue: Queue<TimedPacket>, count: Int) {
        var remaining = count
        while (queue.isNotEmpty() && remaining-- > 0) {
            PacketUtils.sendPacketNoEvent(queue.poll().packet)
        }
    }

    private fun flushQueue(queue: Queue<TimedPacket>) {
        while (queue.isNotEmpty()) {
            PacketUtils.sendPacketNoEvent(queue.poll().packet)
        }
    }

    private fun flushAllPackets() {
        flushQueue(inboundQueue)
        flushQueue(outboundQueue)
        lastStartBlinkTime = -1
    }

    private fun enableBlink() {
        lastStartBlinkTime = System.currentTimeMillis()
        Blink.enable()
    }

    private fun disableBlink() {
        lastDisableTime = System.currentTimeMillis()
        Blink.disable()
        flushAllPackets()
    }

    private fun debugMessage(message: String) {
        mc.thePlayer.addChatMessage(StringUtils.getRed("[FakeLag] $message"))
    }

    override val tag: String
        get() = "${target?.name?.take(3) ?: "N/A"}:${inboundQueue.size}/${outboundQueue.size}"
}