package com.nic.lgpower

import android.content.Context

/**
 * Relocation: a Saved TV that came back from a power cut or a Wi-Fi drop often has a new DHCP
 * address. It is recognised by its Fingerprint and its saved address updated, so the remote keeps
 * working with the same client key and no new pairing. Only a Fingerprint match ever moves a TV:
 * sending a Saved TV's key to a different set would show a pairing prompt on the wrong screen.
 */

/** Saved TV id → new address, for every Saved TV whose Fingerprint turned up somewhere else. */
internal fun relocations(saved: List<TvStore.Tv>, found: List<TvDiscovery.Found>): Map<String, String> =
    saved.mapNotNull { tv ->
        if (tv.udn.isBlank()) return@mapNotNull null
        val at = found.filter { it.udn?.equals(tv.udn, ignoreCase = true) == true }.map { it.ip }
        // A TV on both cable and Wi-Fi answers twice; the address it already has is still good
        if (at.isEmpty() || tv.ip in at) null else tv.id to at.first()
    }.toMap()

/** The TV a first run with no saved address connects to: the active TV's own if it is there, else the first found. */
internal fun firstRunPick(activeUdn: String?, found: List<TvDiscovery.Found>): String? =
    found.firstOrNull { !activeUdn.isNullOrBlank() && it.udn.equals(activeUdn, ignoreCase = true) }?.ip
        ?: found.firstOrNull()?.ip

/**
 * Keeps scans from piling up. Away from home every power check says "unreachable", and each
 * scan is a few seconds of probing the whole subnet, so unforced ones wait out a cooldown.
 * Forced ones (the phone joined a network, a wake is in progress) skip it but never overlap.
 */
internal class RelocationGate(private val cooldownMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private var running = false
    private var lastStart: Long? = null

    @Synchronized fun tryStart(force: Boolean): Boolean {
        if (running) return false
        val last = lastStart
        if (!force && last != null && clock() - last < cooldownMs) return false
        running = true
        lastStart = clock()
        return true
    }

    @Synchronized fun finish() { running = false }

    /** The app came back to the front: the next scan may run straight away. */
    @Synchronized fun reset() { lastStart = null }
}

/**
 * The address and Fingerprint in a webOS TV's SSDP "alive" Announcement, or null for anything
 * else (other devices, byebye, search responses, no Fingerprint). [sender] stands in when the
 * message carries no LOCATION.
 */
internal fun announcementOf(message: String, sender: String): TvDiscovery.Found? {
    val lines = message.lines()
    if (lines.firstOrNull()?.startsWith("NOTIFY", ignoreCase = true) != true) return null
    fun header(name: String) = lines.firstOrNull { it.startsWith("$name:", ignoreCase = true) }
        ?.substringAfter(":")?.trim()
    if (header("NTS")?.equals("ssdp:alive", ignoreCase = true) != true) return null
    if (header("NT")?.contains("webos-second-screen", ignoreCase = true) != true) return null
    val udn = header("USN")?.let { Regex("^uuid:([^:]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        ?.takeIf { it.isNotBlank() } ?: return null
    val ip = header("LOCATION")?.let { runCatching { java.net.URL(it).host }.getOrNull() }
        ?.takeIf { it.isNotEmpty() } ?: sender
    return TvDiscovery.Found(ip, udn)
}

/** Runs Relocation against the network and writes the results to the Saved TVs. */
object Relocator {

    private const val COOLDOWN_MS = 15_000L
    internal val gate = RelocationGate(COOLDOWN_MS)

    /** The TV can only be on a LAN or the phone's hotspot; on mobile data alone there is nothing to scan. */
    fun onLan(context: Context): Boolean =
        LanNetwork.get(context) != null || LanNetwork.tetheredAddresses(context).isNotEmpty()

    /**
     * Blocking — call from a background thread. Scans the network and moves every Saved TV found
     * at a new address. True when the active TV's address changed.
     */
    fun scan(context: Context, force: Boolean = false): Boolean {
        if (!onLan(context) || !gate.tryStart(force)) return false
        return try {
            apply(context, TvDiscovery.discoverDetailed(context))
        } finally {
            gate.finish()
        }
    }

    /** Asks the TV at [ip] for its Fingerprint, by whichever way it answers. Blocking. */
    fun lookUpFingerprint(context: Context, ip: String): String? =
        TvDiscovery.fingerprintAnyWay(context, ip)

    /** Moves the Saved TVs [found] at new addresses. True when the active TV's address changed. */
    fun apply(context: Context, found: List<TvDiscovery.Found>): Boolean {
        val prefs = context.applicationContext.getSharedPreferences("webos", Context.MODE_PRIVATE)
        val saved = TvStore.list(prefs)
        val moves = relocations(saved, found)
        return TvStore.relocate(prefs, moves)
    }
}
