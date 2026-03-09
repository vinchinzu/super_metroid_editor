package com.supermetroid.editor.emulator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP client for RetroArch's Network Command interface.
 * Uses READ_CORE_RAM / WRITE_CORE_RAM commands over UDP port 55355 (default).
 *
 * Important: READ_CORE_RAM uses WRAM-relative addresses (0x0998, not 0x7E0998).
 * Callers pass full SNES bus addresses (0x7Exxxx); this client strips the bank.
 */
class RetroArchNwaClient(
    private val host: String = "localhost",
    private val port: Int = 55355,
    private val timeoutMs: Int = 5000,
) {
    private var socket: DatagramSocket? = null
    private val address = InetAddress.getByName(host)
    private val udpMutex = Mutex()

    val isConnected: Boolean
        get() {
            val s = socket
            return s != null && !s.isClosed
        }

    fun connect() {
        socket?.close()
        socket = DatagramSocket().apply { soTimeout = timeoutMs }
    }

    /** Close and reopen the UDP socket. Clears any pending ICMP errors. */
    fun reconnect() {
        connect()
    }

    fun disconnect() {
        socket?.close()
        socket = null
    }

    /**
     * Read [size] bytes from SNES core RAM at [address].
     * [address] is a full SNES bus address (e.g. 0x7E0998); the 0x7E bank is stripped
     * automatically to produce the WRAM-relative offset that READ_CORE_RAM expects.
     */
    suspend fun readMemory(address: Int, size: Int): ByteArray = withContext(Dispatchers.IO) {
        val sock = socket ?: throw IllegalStateException("Not connected")
        val wramAddr = toWramOffset(address)
        val command = "READ_CORE_RAM ${wramAddr.toString(16).uppercase()} $size"

        udpMutex.withLock {
            val sendData = command.toByteArray()
            sock.send(DatagramPacket(sendData, sendData.size, this@RetroArchNwaClient.address, port))

            val buf = ByteArray(4096)
            val packet = DatagramPacket(buf, buf.size)
            sock.receive(packet)

            val response = String(packet.data, 0, packet.length).trim()
            parseReadResponse(response, size)
        }
    }

    /**
     * Write [data] bytes to SNES core RAM at [address].
     * [address] is a full SNES bus address; the 0x7E bank is stripped automatically.
     */
    suspend fun writeMemory(address: Int, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val sock = socket ?: throw IllegalStateException("Not connected")
        val wramAddr = toWramOffset(address)
        val hexData = data.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        val command = "WRITE_CORE_RAM ${wramAddr.toString(16).uppercase()} $hexData"

        udpMutex.withLock {
            val sendData = command.toByteArray()
            sock.send(DatagramPacket(sendData, sendData.size, this@RetroArchNwaClient.address, port))

            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            sock.receive(packet)

            val response = String(packet.data, 0, packet.length).trim()
            response.startsWith("WRITE_CORE_RAM")
        }
    }

    /**
     * Send a simple probe to verify RetroArch is listening and a core is loaded.
     */
    suspend fun probe(): Boolean {
        return try {
            readMemory(GAME_STATE_ADDR, 2)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseReadResponse(response: String, expectedSize: Int): ByteArray {
        val parts = response.split(" ")
        if (parts.size < 3 || parts[0] != "READ_CORE_RAM") {
            if (parts[0] == "WRITE_CORE_RAM") {
                return ByteArray(expectedSize)
            }
            throw IllegalArgumentException("Unexpected response: $response")
        }
        if (parts.size >= 3 && parts[2] == "-1") {
            throw IllegalStateException("NWA: memory not ready (response: $response)")
        }
        val bytes = parts.drop(2).map { it.toInt(16).toByte() }.toByteArray()
        if (bytes.size != expectedSize) {
            System.err.println("[NWA] Expected $expectedSize bytes, got ${bytes.size}")
        }
        return bytes
    }

    companion object {
        const val DEFAULT_PORT = 55355
        /** SNES WRAM address for Super Metroid game state. */
        private const val GAME_STATE_ADDR = 0x7E0998

        /**
         * Convert a full SNES bus address (0x7Exxxx or 0x7Fxxxx) to WRAM offset.
         * READ_CORE_RAM expects offsets into the core's RAM, not full bus addresses.
         * WRAM bank 0x7E maps to offset 0x0000–0xFFFF, bank 0x7F to 0x10000–0x1FFFF.
         */
        fun toWramOffset(address: Int): Int {
            val bank = (address shr 16) and 0xFF
            return when (bank) {
                0x7E -> address and 0xFFFF
                0x7F -> 0x10000 + (address and 0xFFFF)
                else -> address  // pass through non-WRAM addresses as-is
            }
        }
    }
}
