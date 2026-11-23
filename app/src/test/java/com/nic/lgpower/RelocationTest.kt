package com.nic.lgpower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelocationTest {
    private fun tv(id: String, ip: String, udn: String = "") = TvStore.Tv(id, id, ip, "", "key-$id", udn)
    private fun found(ip: String, udn: String?) = TvDiscovery.Found(ip, udn)

    // ── relocations ───────────────────────────────────────────────────────────

    @Test fun aSavedTvFoundAtANewAddressMoves() {
        val moves = relocations(listOf(tv("a", "192.168.1.20", "u-a")), listOf(found("192.168.1.31", "u-a")))
        assertEquals(mapOf("a" to "192.168.1.31"), moves)
    }

    @Test fun aSavedTvFoundWhereItWasStaysPut() {
        assertEquals(emptyMap<String, String>(),
            relocations(listOf(tv("a", "192.168.1.20", "u-a")), listOf(found("192.168.1.20", "u-a"))))
    }

    // Moving a TV onto another one's key would show a pairing prompt on the wrong set
    @Test fun aTvWithoutAFingerprintIsNeverMoved() {
        assertEquals(emptyMap<String, String>(),
            relocations(listOf(tv("a", "192.168.1.20")), listOf(found("192.168.1.31", "u-x"))))
    }

    @Test fun aTvThatAnsweredWithoutAFingerprintIsNeverAMatch() {
        assertEquals(emptyMap<String, String>(),
            relocations(listOf(tv("a", "192.168.1.20", "u-a")), listOf(found("192.168.1.31", null))))
    }

    @Test fun aDifferentTvAtTheOldAddressDoesNotMoveIt() {
        assertEquals(emptyMap<String, String>(),
            relocations(listOf(tv("a", "192.168.1.20", "u-a")), listOf(found("192.168.1.20", "u-b"))))
    }

    @Test fun fingerprintsMatchRegardlessOfCase() {
        assertEquals(mapOf("a" to "192.168.1.31"),
            relocations(listOf(tv("a", "192.168.1.20", "ABC-123")), listOf(found("192.168.1.31", "abc-123"))))
    }

    @Test fun everyMovedTvIsUpdatedInOneRun() {
        val saved = listOf(tv("a", "192.168.1.20", "u-a"), tv("b", "192.168.1.21", "u-b"), tv("c", "192.168.1.22", "u-c"))
        val moves = relocations(saved, listOf(found("192.168.1.21", "u-a"), found("192.168.1.20", "u-b")))
        assertEquals(mapOf("a" to "192.168.1.21", "b" to "192.168.1.20"), moves)
    }

    @Test fun duplicateEntriesForOneTvBothMove() {
        val saved = listOf(tv("a", "192.168.1.20", "u-a"), tv("a2", "192.168.1.20", "u-a"))
        assertEquals(mapOf("a" to "192.168.1.31", "a2" to "192.168.1.31"),
            relocations(saved, listOf(found("192.168.1.31", "u-a"))))
    }

    // A TV on both cable and Wi-Fi answers twice; the address it already has is still good
    @Test fun aTvAnsweringOnTwoAddressesKeepsTheSavedOne() {
        assertEquals(emptyMap<String, String>(),
            relocations(listOf(tv("a", "192.168.1.20", "u-a")), listOf(found("192.168.1.31", "u-a"), found("192.168.1.20", "u-a"))))
    }

    @Test fun aTvOnTheHotspotSubnetIsFollowed() {
        assertEquals(mapOf("a" to "192.168.43.7"),
            relocations(listOf(tv("a", "192.168.1.20", "u-a")), listOf(found("192.168.43.7", "u-a"))))
    }

    // ── firstRunPick ──────────────────────────────────────────────────────────

    @Test fun firstRunPrefersTheActiveTvsFingerprint() {
        assertEquals("192.168.1.31", firstRunPick("u-a", listOf(found("192.168.1.30", "u-b"), found("192.168.1.31", "u-a"))))
    }

    @Test fun firstRunFallsBackToTheFirstTvFound() {
        assertEquals("192.168.1.30", firstRunPick("u-a", listOf(found("192.168.1.30", "u-b"))))
        assertEquals("192.168.1.30", firstRunPick(null, listOf(found("192.168.1.30", null))))
    }

    @Test fun firstRunWithNothingFoundPicksNothing() {
        assertNull(firstRunPick("u-a", emptyList()))
    }

    // ── RelocationGate ────────────────────────────────────────────────────────

    @Test fun aScanWithinTheCooldownIsSkipped() {
        var now = 0L
        val gate = RelocationGate(15_000) { now }
        assertTrue(gate.tryStart(force = false)); gate.finish()
        now = 14_999
        assertFalse(gate.tryStart(force = false))
        now = 15_000
        assertTrue(gate.tryStart(force = false))
    }

    @Test fun aForcedScanIgnoresTheCooldown() {
        var now = 0L
        val gate = RelocationGate(15_000) { now }
        assertTrue(gate.tryStart(force = false)); gate.finish()
        now = 1_000
        assertTrue(gate.tryStart(force = true))
    }

    @Test fun scansNeverOverlapEvenWhenForced() {
        val gate = RelocationGate(15_000) { 0L }
        assertTrue(gate.tryStart(force = true))
        assertFalse(gate.tryStart(force = true))
        gate.finish()
        assertTrue(gate.tryStart(force = true))
    }

    @Test fun resettingLetsTheNextScanThrough() {
        var now = 0L
        val gate = RelocationGate(15_000) { now }
        assertTrue(gate.tryStart(force = false)); gate.finish()
        now = 2_000
        gate.reset()
        assertTrue(gate.tryStart(force = false))
    }

    // ── announcementOf ────────────────────────────────────────────────────────

    private val alive = "NOTIFY * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "CACHE-CONTROL: max-age=1800\r\n" +
        "LOCATION: http://192.168.1.31:1840/abc/description.xml\r\n" +
        "NT: urn:lge-com:service:webos-second-screen:1\r\n" +
        "NTS: ssdp:alive\r\n" +
        "USN: uuid:4f1c2a9e-0000-1111-2222-a1b2c3d4e5f6::urn:lge-com:service:webos-second-screen:1\r\n\r\n"

    @Test fun anAliveAnnouncementGivesAddressAndFingerprint() {
        assertEquals(found("192.168.1.31", "4f1c2a9e-0000-1111-2222-a1b2c3d4e5f6"), announcementOf(alive, "192.168.1.31"))
    }

    @Test fun theSenderAddressIsUsedWhenThereIsNoLocation() {
        val noLocation = alive.lines().filterNot { it.startsWith("LOCATION") }.joinToString("\r\n")
        assertEquals("192.168.1.32", announcementOf(noLocation, "192.168.1.32")?.ip)
    }

    @Test fun aByebyeIsNotAnAnnouncement() {
        assertNull(announcementOf(alive.replace("ssdp:alive", "ssdp:byebye"), "192.168.1.31"))
    }

    @Test fun otherDevicesAnnouncementsAreIgnored() {
        val router = alive.replace("urn:lge-com:service:webos-second-screen:1", "urn:schemas-upnp-org:device:InternetGatewayDevice:1")
        assertNull(announcementOf(router, "192.168.1.1"))
    }

    @Test fun searchResponsesAreNotAnnouncements() {
        assertNull(announcementOf(alive.replace("NOTIFY * HTTP/1.1", "HTTP/1.1 200 OK"), "192.168.1.31"))
    }

    @Test fun anAnnouncementWithoutAFingerprintIsIgnored() {
        val noUsn = alive.lines().filterNot { it.startsWith("USN") }.joinToString("\r\n")
        assertNull(announcementOf(noUsn, "192.168.1.31"))
    }
}
