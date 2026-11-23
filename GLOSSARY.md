# LG TV Remote

An Android remote control for LG webOS TVs that talks to the TV over the local network.

## Language

**Saved TV**:
A TV the app has paired with and keeps in its list, together with the credentials to control it without pairing again.
_Avoid_: Paired TV, known TV, device

**Active TV**:
The one Saved TV the remote is currently controlling.
_Avoid_: Current TV, selected TV

**Fingerprint**:
The stable identity a TV announces on the network, which stays the same when its address changes.
_Avoid_: UDN, UUID, device ID

**Announcement**:
The message a TV sends to the local network when it comes online, carrying its Fingerprint and current address.
_Avoid_: Broadcast, ping, alive

**Relocation**:
Finding a Saved TV at a new network address by its Fingerprint and updating it, with no new pairing.
_Avoid_: Rediscovery, re-pairing, auto-reconnect
