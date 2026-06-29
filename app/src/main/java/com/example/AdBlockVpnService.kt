package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID

data class DnsLog(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val domain: String,
    val isBlocked: Boolean,
    val upstream: String
)

object AdBlockManager {
    private const val PREFS_NAME = "adblock_prefs"
    private const val KEY_BLOCKED = "total_blocked"
    private const val KEY_ALLOWED = "total_allowed"
    private const val KEY_PROFILE = "dns_profile"
    private const val KEY_CUSTOM_DNS = "custom_dns"
    private const val KEY_BLOCKLIST = "custom_blocklist"
    private const val KEY_ALLOWLIST = "custom_allowlist"

    val isRunning = MutableStateFlow(false)
    val connectionStartTime = MutableStateFlow<Long?>(null)
    
    val totalBlocked = MutableStateFlow(0)
    val totalAllowed = MutableStateFlow(0)
    
    val logs = MutableStateFlow<List<DnsLog>>(emptyList())
    
    val customBlocklist = MutableStateFlow<Set<String>>(emptySet())
    val customAllowlist = MutableStateFlow<Set<String>>(emptySet())
    
    val dnsProfile = MutableStateFlow("adguard")
    val customDnsIp = MutableStateFlow("94.140.14.14")
    
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        totalBlocked.value = prefs.getInt(KEY_BLOCKED, 0)
        totalAllowed.value = prefs.getInt(KEY_ALLOWED, 0)
        dnsProfile.value = prefs.getString(KEY_PROFILE, "adguard") ?: "adguard"
        customDnsIp.value = prefs.getString(KEY_CUSTOM_DNS, "94.140.14.14") ?: "94.140.14.14"
        customBlocklist.value = prefs.getStringSet(KEY_BLOCKLIST, emptySet()) ?: emptySet()
        customAllowlist.value = prefs.getStringSet(KEY_ALLOWLIST, emptySet()) ?: emptySet()
    }
    
    fun persistStats() {
        if (::prefs.isInitialized) {
            prefs.edit()
                .putInt(KEY_BLOCKED, totalBlocked.value)
                .putInt(KEY_ALLOWED, totalAllowed.value)
                .apply()
        }
    }

    fun saveProfile(profile: String, customIp: String) {
        dnsProfile.value = profile
        customDnsIp.value = customIp
        if (::prefs.isInitialized) {
            prefs.edit()
                .putString(KEY_PROFILE, profile)
                .putString(KEY_CUSTOM_DNS, customIp)
                .apply()
        }
    }

    fun addCustomBlock(domain: String) {
        val current = customBlocklist.value.toMutableSet()
        current.add(domain.lowercase().trim())
        customBlocklist.value = current
        if (::prefs.isInitialized) {
            prefs.edit().putStringSet(KEY_BLOCKLIST, current).apply()
        }
    }

    fun removeCustomBlock(domain: String) {
        val current = customBlocklist.value.toMutableSet()
        current.remove(domain.lowercase().trim())
        customBlocklist.value = current
        if (::prefs.isInitialized) {
            prefs.edit().putStringSet(KEY_BLOCKLIST, current).apply()
        }
    }

    fun addCustomAllow(domain: String) {
        val current = customAllowlist.value.toMutableSet()
        current.add(domain.lowercase().trim())
        customAllowlist.value = current
        if (::prefs.isInitialized) {
            prefs.edit().putStringSet(KEY_ALLOWLIST, current).apply()
        }
    }

    fun removeCustomAllow(domain: String) {
        val current = customAllowlist.value.toMutableSet()
        current.remove(domain.lowercase().trim())
        customAllowlist.value = current
        if (::prefs.isInitialized) {
            prefs.edit().putStringSet(KEY_ALLOWLIST, current).apply()
        }
    }

    fun addLog(log: DnsLog) {
        val current = logs.value.toMutableList()
        current.add(0, log)
        if (current.size > 100) {
            current.removeAt(current.size - 1)
        }
        logs.value = current
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    fun resetStats() {
        totalBlocked.value = 0
        totalAllowed.value = 0
        persistStats()
    }
}

class AdBlockVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.adblock.START"
        const val ACTION_STOP = "com.example.adblock.STOP"
        private const val TAG = "AdBlockVpnService"
        private const val CHANNEL_ID = "adblock_vpn_channel"
        private const val NOTIFICATION_ID = 4221
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isVpnRunning = false

    private val adKeywords = listOf(
        "doubleclick.net", "googlesyndication.com", "googleads", "google-analytics.com",
        "adservice.google", "adnxs.com", "adcolony.com", "applovin.com", "unityads",
        "vungle.com", "mopub.com", "applixir.com", "chartboost.com", "ironsrc.com",
        "adjust.com", "branch.io", "kochava.com", "flurry.com", "mixpanel.com",
        "amplitude.com", "scorecardresearch.com", "quantserve.com", "openx.net",
        "adserver", "adsystem", "adtrack", "telemetry", "analytics", "crashlytics",
        "ad-delivery", "adsrvr.org", "popads.net", "propellerads.com"
    )

    override fun onCreate() {
        super.onCreate()
        AdBlockManager.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_START -> startVpn()
                ACTION_STOP -> stopVpn()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        if (isVpnRunning) return
        isVpnRunning = true
        AdBlockManager.isRunning.value = true
        AdBlockManager.connectionStartTime.value = System.currentTimeMillis()

        // Show foreground service notification
        val notification = getNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        vpnThread = Thread({ runVpnLoop() }, "AdBlockVpnThread").apply { start() }
        Log.i(TAG, "AdBlock VPN Service started successfully")
    }

    private fun stopVpn() {
        if (!isVpnRunning) return
        isVpnRunning = false
        AdBlockManager.isRunning.value = false
        AdBlockManager.connectionStartTime.value = null

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }

        vpnThread?.interrupt()
        vpnThread = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "AdBlock VPN Service stopped successfully")
    }

    private fun runVpnLoop() {
        try {
            // Establish VPN interface
            val builder = Builder()
            builder.setSession("AdBlocker")
            // 10.0.0.1 is local VPN address.
            builder.addAddress("10.0.0.1", 24)
            
            // Route DNS requests to local address
            builder.addDnsServer("10.0.0.1")
            builder.addRoute("10.0.0.1", 32)

            // Configure MTU
            builder.setMtu(1500)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopVpn()
                return
            }

            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val inputChannel = inputStream.channel
            val packetBuffer = ByteBuffer.allocate(32767)

            while (isVpnRunning && !Thread.currentThread().isInterrupted) {
                packetBuffer.clear()
                val length = inputChannel.read(packetBuffer)
                if (length > 0) {
                    packetBuffer.flip()
                    val packet = ByteArray(length)
                    packetBuffer.get(packet)
                    
                    processPacket(packet, length, outputStream)
                } else {
                    Thread.sleep(15)
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "VPN loop interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Exception in VPN loop", e)
        } finally {
            stopVpn()
        }
    }

    private fun processPacket(packet: ByteArray, length: Int, outputStream: FileOutputStream) {
        try {
            // Check version (must be IPv4)
            val version = (packet[0].toInt() and 0xF0) shr 4
            if (version != 4) return

            // Protocol (must be UDP = 17)
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 17) return

            // Destination port (must be DNS = 53)
            val destPort = ((packet[22].toInt() and 0xFF) shl 8) or (packet[23].toInt() and 0xFF)
            if (destPort != 53) return

            // Parse domain name from DNS payload (starts at byte 28)
            val domain = parseDnsQueryDomain(packet, length) ?: return

            // Match domain against blocklists
            val isBlocked = isAdDomain(domain)
            val upstreamIp = getUpstreamDnsIp()

            // Log entry
            AdBlockManager.addLog(
                DnsLog(
                    domain = domain,
                    isBlocked = isBlocked,
                    upstream = upstreamIp
                )
            )

            if (isBlocked) {
                AdBlockManager.totalBlocked.value++
                AdBlockManager.persistStats()

                val response = createBlockedDnsResponse(packet, length)
                synchronized(outputStream) {
                    outputStream.write(response)
                }
            } else {
                AdBlockManager.totalAllowed.value++
                AdBlockManager.persistStats()

                forwardDnsQuery(packet, length, domain, upstreamIp, outputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing DNS packet", e)
        }
    }

    private fun isAdDomain(domain: String): Boolean {
        val cleanDomain = domain.lowercase().trim()

        // 1. Check custom allowlist (overrides everything)
        if (AdBlockManager.customAllowlist.value.any { cleanDomain == it || cleanDomain.endsWith(".$it") }) {
            return false
        }

        // 2. Check custom blocklist
        if (AdBlockManager.customBlocklist.value.any { cleanDomain == it || cleanDomain.endsWith(".$it") }) {
            return true
        }

        // 3. Check static keywords list
        return adKeywords.any { keyword ->
            cleanDomain == keyword || cleanDomain.endsWith(".$keyword") || cleanDomain.contains(".$keyword.")
        }
    }

    private fun getUpstreamDnsIp(): String {
        return when (AdBlockManager.dnsProfile.value) {
            "cloudflare" -> "1.1.1.3" // Cloudflare Family (Malware + Ads blocking)
            "google" -> "8.8.8.8"     // Google DNS (Standard resolution, relies on local filter)
            "custom" -> AdBlockManager.customDnsIp.value
            else -> "94.140.14.14"    // AdGuard Default DNS (Heavy filtering)
        }
    }

    private fun parseDnsQueryDomain(packet: ByteArray, totalLength: Int, startOffset: Int = 28): String? {
        try {
            // DNS Header is 12 bytes. Validate we have a question
            if (totalLength < startOffset + 12) return null
            val qCount = ((packet[startOffset + 4].toInt() and 0xFF) shl 8) or (packet[startOffset + 5].toInt() and 0xFF)
            if (qCount <= 0) return null

            // Question Section begins at startOffset + 12
            var offset = startOffset + 12
            val domain = StringBuilder()
            while (offset < totalLength) {
                val labelLen = packet[offset].toInt() and 0xFF
                if (labelLen == 0) {
                    break
                }
                offset++
                if (offset + labelLen > totalLength) return null
                if (domain.isNotEmpty()) {
                    domain.append(".")
                }
                for (i in 0 until labelLen) {
                    domain.append(packet[offset + i].toChar())
                }
                offset += labelLen
            }
            return domain.toString()
        } catch (e: Exception) {
            return null
        }
    }

    private fun forwardDnsQuery(
        queryPacket: ByteArray,
        length: Int,
        domain: String,
        upstreamIp: String,
        outputStream: FileOutputStream
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var socket: DatagramSocket? = null
            try {
                val dnsPayload = queryPacket.sliceArray(28 until length)
                socket = DatagramSocket()
                protect(socket) // Ensure this socket bypasses the VPN interface
                socket.soTimeout = 2500 // Timeout 2.5s
                
                val upstreamAddress = InetAddress.getByName(upstreamIp)
                val sendPacket = DatagramPacket(dnsPayload, dnsPayload.size, upstreamAddress, 53)
                socket.send(sendPacket)

                val responseBuffer = ByteArray(1500)
                val receivePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(receivePacket)

                val responseLength = receivePacket.length
                val responseDnsPayload = responseBuffer.sliceArray(0 until responseLength)

                val fullResponsePacket = createDnsResponsePacket(queryPacket, responseDnsPayload, responseLength)
                synchronized(outputStream) {
                    if (isVpnRunning) {
                        outputStream.write(fullResponsePacket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed forwarding DNS query for $domain to $upstreamIp: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    private fun createBlockedDnsResponse(queryPacket: ByteArray, length: Int): ByteArray {
        val response = queryPacket.copyOf(length + 16) // Reserve 16 bytes for answer resource record
        
        // Swap IPv4 Source and Destination IPs
        val srcIp = queryPacket.sliceArray(12..15)
        val destIp = queryPacket.sliceArray(16..19)
        System.arraycopy(destIp, 0, response, 12, 4)
        System.arraycopy(srcIp, 0, response, 16, 4)
        
        // Swap UDP Source and Destination Ports
        val srcPort = queryPacket.sliceArray(20..21)
        val destPort = queryPacket.sliceArray(22..23)
        System.arraycopy(destPort, 0, response, 20, 2)
        System.arraycopy(srcPort, 0, response, 22, 2)
        
        // Update DNS flags to "Standard query response, No error" (0x8180)
        response[30] = 0x81.toByte()
        response[31] = 0x80.toByte()
        
        // Set Answer RR Count to 1
        response[34] = 0x00.toByte()
        response[35] = 0x01.toByte()
        
        // Locate end of DNS Question name
        var offset = 40
        while (offset < length) {
            val labelLen = response[offset].toInt() and 0xFF
            if (labelLen == 0) {
                offset++
                break
            }
            offset += labelLen + 1
        }
        // Skip 2 bytes of QType and 2 bytes of QClass
        offset += 4
        
        // Append DNS Answer Record resolving to 0.0.0.0
        // Name Pointer pointing back to original Question Name (0xc00c)
        response[offset] = 0xc0.toByte()
        response[offset + 1] = 0x0c.toByte()
        // Type A (0x0001)
        response[offset + 2] = 0x00.toByte()
        response[offset + 3] = 0x01.toByte()
        // Class IN (0x0001)
        response[offset + 4] = 0x00.toByte()
        response[offset + 5] = 0x01.toByte()
        // TTL = 60 seconds (0x0000003c)
        response[offset + 6] = 0x00.toByte()
        response[offset + 7] = 0x00.toByte()
        response[offset + 8] = 0x00.toByte()
        response[offset + 9] = 0x3c.toByte()
        // Data length = 4 bytes (0x0004)
        response[offset + 10] = 0x00.toByte()
        response[offset + 11] = 0x04.toByte()
        // IP Address = 0.0.0.0
        response[offset + 12] = 0x00.toByte()
        response[offset + 13] = 0x00.toByte()
        response[offset + 14] = 0x00.toByte()
        response[offset + 15] = 0x00.toByte()
        
        val newIpLength = offset + 16
        val newDnsLength = newIpLength - 28
        
        // Update IPv4 Total Length
        response[2] = ((newIpLength shr 8) and 0xFF).toByte()
        response[3] = (newIpLength and 0xFF).toByte()
        
        // Update UDP Length
        val udpLength = newDnsLength + 8
        response[24] = ((udpLength shr 8) and 0xFF).toByte()
        response[25] = (udpLength and 0xFF).toByte()
        
        // Reset Checksums to be recomputed
        response[26] = 0
        response[27] = 0
        response[10] = 0
        response[11] = 0
        
        // Compute IPv4 Header Checksum
        var checksum = 0
        for (i in 0 until 20 step 2) {
            val word = ((response[i].toInt() and 0xFF) shl 8) or (response[i + 1].toInt() and 0xFF)
            checksum += word
        }
        while (checksum shr 16 != 0) {
            checksum = (checksum and 0xFFFF) + (checksum shr 16)
        }
        checksum = checksum.inv() and 0xFFFF
        response[10] = ((checksum shr 8) and 0xFF).toByte()
        response[11] = (checksum and 0xFF).toByte()
        
        return response.sliceArray(0 until newIpLength)
    }

    private fun createDnsResponsePacket(queryPacket: ByteArray, responseDnsPayload: ByteArray, responseDnsLength: Int): ByteArray {
        val totalLength = 20 + 8 + responseDnsLength
        val response = ByteArray(totalLength)
        
        // IPv4 Header
        response[0] = 0x45.toByte() // Version & IHL
        response[1] = 0x00.toByte() // TOS
        response[2] = ((totalLength shr 8) and 0xFF).toByte()
        response[3] = (totalLength and 0xFF).toByte()
        response[4] = queryPacket[4] // Identification
        response[5] = queryPacket[5]
        response[6] = 0x00.toByte() // Flags & Fragment Offset
        response[7] = 0x00.toByte()
        response[8] = 64.toByte() // TTL
        response[9] = 17.toByte() // Protocol UDP
        
        // Swap IPs
        System.arraycopy(queryPacket, 16, response, 12, 4) // Src IP
        System.arraycopy(queryPacket, 12, response, 16, 4) // Dest IP
        
        // UDP Header
        System.arraycopy(queryPacket, 22, response, 20, 2) // Src Port
        System.arraycopy(queryPacket, 20, response, 22, 2) // Dest Port
        
        val udpLength = 8 + responseDnsLength
        response[24] = ((udpLength shr 8) and 0xFF).toByte()
        response[25] = (udpLength and 0xFF).toByte()
        response[26] = 0
        response[27] = 0
        
        // DNS Payload
        System.arraycopy(responseDnsPayload, 0, response, 28, responseDnsLength)
        
        // Recompute IP Checksum
        response[10] = 0
        response[11] = 0
        var checksum = 0
        for (i in 0 until 20 step 2) {
            val word = ((response[i].toInt() and 0xFF) shl 8) or (response[i + 1].toInt() and 0xFF)
            checksum += word
        }
        while (checksum shr 16 != 0) {
            checksum = (checksum and 0xFFFF) + (checksum shr 16)
        }
        checksum = checksum.inv() and 0xFFFF
        response[10] = ((checksum shr 8) and 0xFF).toByte()
        response[11] = (checksum and 0xFF).toByte()
        
        return response
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ad Blocker Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays the current status of the system-wide Ad Blocker"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun getNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val profileStr = when (AdBlockManager.dnsProfile.value) {
            "cloudflare" -> "Cloudflare Family DNS"
            "google" -> "Google DNS"
            "custom" -> "Custom DNS (${AdBlockManager.customDnsIp.value})"
            else -> "AdGuard DNS (Recommended)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ad Blocker Shield Active")
            .setContentText("Protecting your apps using $profileStr")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
