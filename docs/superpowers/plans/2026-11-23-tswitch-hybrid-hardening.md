# T-Switch Hybrid Hardening & 100% Bug Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve all 17 audited P0/P1/P2 bugs across SIP/TLS/RTP/Telecom/SMS/Kiosk/UI and implement the deterministic 2-mode hybrid routing architecture (`HYBRID_LOCAL_IP` when custom SIP is empty vs `CUSTOM_SIP_WITH_PUSH_WAKE` only when a custom SIP server is entered) using strict TDD.

**Architecture:** Extract pure Kotlin decision/codec/framing modules (`RoutingPolicy`, `LocalIpPinController`, `CommandAuthorization`, `TlsFrameBuffer`, `PcmuCodec`, `HotspotClassifier`, `CallFailoverPolicy`) so every protocol, security, routing, and audio invariant is proven by fast JUnit 4 tests on JVM before wiring into `SipClient`, `SipCall`, `PushWakeController`, `CallOrchestrator`, `RtpSession`, `GsmCallManager`, `GatewayService`, and `MainActivity`. In `HYBRID_LOCAL_IP`, `GatewayService` runs both the local UDP `:5060` registrar (pinned to `192.168.1.85` on `192.168.1.x` Wi-Fi / Hotspot) and a persistent `sip.linphone.org:5061` TLS client; in `CUSTOM_SIP_WITH_PUSH_WAKE`, all calls/media/SMS run through the user's custom SIP server while `PushWakeController` operates strictly in `wakeOnly = true` mode.

**Tech Stack:** Kotlin (JVM 1.8), Android SDK 34 (`minSdk 26`), Gradle 8.5 (`./gradlew.bat testDebugUnitTest --no-daemon`), JUnit 4 (`junit:junit:4.13.2`), Android Telecom / AudioManager / RootShell.

**Spec:** `docs/superpowers/specs/2026-11-23-tswitch-hybrid-hardening-design.md`

## Global Constraints

- `minSdk = 26` (`app/build.gradle.kts:12`) — no unguarded API 28+ calls (`TelephonyManager.getSignalStrength()`, `CellIdentity*.mccString/mncString`).
- **Custom SIP Invariant:** Leaving the custom SIP server blank (or equal to `sip.linphone.org`) MUST keep `RoutingMode.HYBRID_LOCAL_IP` (fixed local IP `192.168.1.85` + Linphone cloud/PushKit). Entering a non-empty custom SIP server switches ALL primary call/media/SMS traffic to that server and forces `PushWakeController` to `wakeOnly = true`.
- **Security Invariant:** All terminal commands (`!`, `$`, custom aliases) remain enabled on both LAN and cloud ONLY when the sender is verified as the paired account (`registeredPeer` on LAN, or exact `receiverUser` + allowed domain on cloud/custom SIP). Any message addressed to a real phone number (`>= 7` digits) is NEVER intercepted as a terminal command.
- **TDD Iron Law:** Every task writes failing unit tests first, verifies RED via `./gradlew.bat testDebugUnitTest --no-daemon`, implements minimal code, verifies GREEN across the entire test suite, and commits.
- **Zero Speaker Leak & Zero Double-Billing:** SIP-bridged calls never leak audio to GM 8's speaker; every outbound SMS is claimed once via `SmsOutbox.claimForDispatch()`.

## Review Focus

1. **Multi-byte UTF-8 Turkish text split across TCP chunks in TLS SIP (`ş, ğ, ı, ö, ü, ç`, emojis):** `TlsFrameBuffer` must measure `Content-Length` strictly in UTF-8 bytes and never decode partial multi-byte sequences mid-frame (pinned in Task 3).
2. **Paired-account spoofing & normal SMS starting with `$` or `!` (e.g., `"$50 gönderdim"` to `+905321234567`, or `"$ id"` from `attacker@sip.linphone.org` to `t-switch_pusher`):** Phone-number targets must always classify as `DIRECT_GSM_SMS`, and non-paired cloud senders must always return `REJECT_FORBIDDEN(403)` (pinned in Task 2).
3. **Custom SIP server accidentally left as `"sip.linphone.org"` or whitespace `"   "` during migration:** `RoutingPolicy.resolve()` must treat empty, whitespace, and `"sip.linphone.org"` as `HYBRID_LOCAL_IP` so existing installs never lose fixed-IP local mode (pinned in Task 1).
4. **Second incoming GSM call (Call Waiting) rejected while Call #1 is bridged:** `GsmCallManager` must keep `activeCall === call1` and must NOT call `restoreAudio()` when `call2` disconnects (pinned in Task 7).
5. **Explicit iPhone rejection (`486 Busy` / `603 Decline`) vs `480`/`404` sleeping phone in `CUSTOM_SIP_WITH_PUSH_WAKE`:** `486`/`603` must terminate cleanly without waking GM 8's ringer or retrying; `480`/`404` during an active `wakeOnly` window must retry custom SIP every `2.0s` up to `20s` (pinned in Task 6).

---

### Task 1: Routing Policy & Fixed Local IP Pinning (`RoutingPolicy`, `LocalIpPinController`)

**Files:**
- Create: `app/src/main/java/com/callagent/gateway/localsend/RoutingPolicy.kt`
- Create: `app/src/main/java/com/callagent/gateway/localsend/LocalIpPinController.kt`
- Test: `app/src/test/java/com/callagent/gateway/localsend/RoutingPolicyTest.kt`
- Test: `app/src/test/java/com/callagent/gateway/localsend/LocalIpPinControllerTest.kt`

**Interfaces:**
- Consumes: `RootShell.exec(...)` (for runtime execution inside `LocalIpPinController.applyPin` / `clearHotspotNat`).
- Produces:
  - `enum class RoutingMode { HYBRID_LOCAL_IP, CUSTOM_SIP_WITH_PUSH_WAKE }`
  - `data class RoutingDecision(val mode: RoutingMode, val customServer: String, val customPort: Int, val customUseTls: Boolean, val pushWakeOnly: Boolean, val pinnedLocalIp: String)`
  - `object RoutingPolicy { fun resolve(customSipServer: String?, customSipPort: Int, customSipTls: Boolean, pinnedLocalIp: String? = "192.168.1.85"): RoutingDecision }`
  - `object LocalIpPinController { const val DEFAULT_PINNED_IP = "192.168.1.85"; fun planWifiPinCommand(iface: String, currentIp: String, pinnedIp: String): String?; fun planHotspotPinCommands(iface: String, apIp: String, pinnedIp: String): List<String>; fun planHotspotCleanupCommands(iface: String, apIp: String, pinnedIp: String): List<String>; fun applyForCurrentNetwork(context: Context, pinnedIp: String): Boolean; fun clearPin(pinnedIp: String = DEFAULT_PINNED_IP) }`

- [ ] **Step 1: Write failing unit tests for `RoutingPolicy` and `LocalIpPinController`**

Create `app/src/test/java/com/callagent/gateway/localsend/RoutingPolicyTest.kt`:
```kotlin
package com.callagent.gateway.localsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPolicyTest {
    @Test
    fun blankOrLinphoneServerSelectsHybridLocalIp() {
        for (raw in listOf(null, "", "   ", "sip.linphone.org", "SIP.LINPHONE.ORG")) {
            val d = RoutingPolicy.resolve(raw, 5060, false, "192.168.1.85")
            assertEquals("raw=$raw", RoutingMode.HYBRID_LOCAL_IP, d.mode)
            assertFalse(d.pushWakeOnly)
            assertEquals("192.168.1.85", d.pinnedLocalIp)
        }
    }

    @Test
    fun customServerSelectsCustomSipWithWakeOnlyPush() {
        val d = RoutingPolicy.resolve("sip.example.com", 5061, false, "192.168.1.85")
        assertEquals(RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE, d.mode)
        assertEquals("sip.example.com", d.customServer)
        assertEquals(5061, d.customPort)
        assertTrue(d.customUseTls)
        assertTrue(d.pushWakeOnly)
    }

    @Test
    fun customServerOn5060RespectsExplicitTlsFlag() {
        val udp = RoutingPolicy.resolve("10.20.30.40", 5060, false)
        assertEquals(RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE, udp.mode)
        assertFalse(udp.customUseTls)
        val tls = RoutingPolicy.resolve("10.20.30.40", 5060, true)
        assertTrue(tls.customUseTls)
    }
}
```

Create `app/src/test/java/com/callagent/gateway/localsend/LocalIpPinControllerTest.kt`:
```kotlin
package com.callagent.gateway.localsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIpPinControllerTest {
    @Test
    fun wifiPinAppliesOnlyOnMatching24Subnet() {
        val cmd = LocalIpPinController.planWifiPinCommand("wlan0", "192.168.1.42", "192.168.1.85")
        assertEquals("ip addr replace 192.168.1.85/24 dev wlan0", cmd)
        assertNull(LocalIpPinController.planWifiPinCommand("wlan0", "192.168.1.85", "192.168.1.85"))
        assertNull(LocalIpPinController.planWifiPinCommand("wlan0", "192.168.0.15", "192.168.1.85"))
        assertNull(LocalIpPinController.planWifiPinCommand("wlan0", "10.0.0.5", "192.168.1.85"))
    }

    @Test
    fun hotspotPinAndCleanupCommandsAreIdempotent() {
        val cmds = LocalIpPinController.planHotspotPinCommands("ap0", "192.168.43.1", "192.168.1.85")
        assertTrue(cmds.any { it.contains("ip addr replace 192.168.1.85/32 dev ap0") })
        assertTrue(cmds.any { it.contains("iptables -t nat -C PREROUTING") && it.contains("--to-destination 192.168.43.1") })
        val cleanup = LocalIpPinController.planHotspotCleanupCommands("ap0", "192.168.43.1", "192.168.1.85")
        assertTrue(cleanup.any { it.contains("iptables -t nat -D PREROUTING") })
        assertTrue(cleanup.any { it.contains("ip addr del 192.168.1.85/32 dev ap0") })
    }
}
```

- [ ] **Step 2: Run tests to verify RED**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.callagent.gateway.localsend.RoutingPolicyTest" --tests "com.callagent.gateway.localsend.LocalIpPinControllerTest" --no-daemon`
Expected: FAIL with unresolved references `RoutingPolicy`, `LocalIpPinController`.

- [ ] **Step 3: Implement `RoutingPolicy.kt` and `LocalIpPinController.kt`**

Implement `RoutingPolicy.resolve(...)` and `LocalIpPinController` pure command planners + root `applyForCurrentNetwork` / `clearPin` methods matching the Interfaces block above. Validate IPv4 format (`\d+.\d+.\d+.\d+` with octets `0..255`) before emitting any shell command to prevent injection.

- [ ] **Step 4: Run full unit test suite to verify GREEN**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --no-daemon`
Expected: PASS (`BUILD SUCCESSFUL`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/callagent/gateway/localsend/RoutingPolicy.kt app/src/main/java/com/callagent/gateway/localsend/LocalIpPinController.kt app/src/test/java/com/callagent/gateway/localsend/RoutingPolicyTest.kt app/src/test/java/com/callagent/gateway/localsend/LocalIpPinControllerTest.kt
git commit -m "feat: add deterministic RoutingPolicy and LocalIpPinController"
```

---

### Task 2: Paired-Account Command & SMS Authorization (`CommandAuthorization`)

**Files:**
- Create: `app/src/main/java/com/callagent/gateway/localsend/CommandAuthorization.kt`
- Test: `app/src/test/java/com/callagent/gateway/localsend/CommandAuthorizationTest.kt`

**Interfaces:**
- Consumes: `RoutingMode` (from Task 1).
- Produces:
  - `sealed class InboundMessageAction` with subtypes:
    - `data class TerminalCommand(val commandText: String)`
    - `data class CallbackCall(val destinationNumber: String)`
    - `data class OutboundSmsCommand(val destinationNumber: String, val text: String)`
    - `data class DirectGsmSms(val destinationNumber: String, val text: String)`
    - `data class Reject(val statusCode: Int, val reason: String)`
  - `object CommandAuthorization { fun classify(isLocalTransport: Boolean, target: String, fromUser: String?, fromDomain: String?, sourcePeer: Pair<String, Int>?, registeredLocalPeer: Pair<String, Int>?, pairedReceiverUser: String, allowedCloudDomains: Set<String>, gatewayUser: String, body: String): InboundMessageAction }`

- [ ] **Step 1: Write failing unit tests for `CommandAuthorization`**

Create `app/src/test/java/com/callagent/gateway/localsend/CommandAuthorizationTest.kt` asserting:
1. Phone number target (`+905321234567`, `05321234567`) with body `"$50 gönderdim"` or `"!acil durum"` returns `DirectGsmSms("+905321234567", ...)` — NEVER `TerminalCommand`.
2. Cloud message to `t-switch_pusher` from `attacker@sip.linphone.org` with body `"$ id"` or `"!ara 05321234567"` returns `Reject(403, ...)`.
3. Cloud message to `t-switch_pusher` from `t-switch_receiver@evil.com` (wrong domain) returns `Reject(403, ...)`.
4. Cloud message to `t-switch_pusher` from `t-switch_receiver@sip.linphone.org` with:
   - `"!ara +905321234567"` or `"+905321234567"` -> `CallbackCall("+905321234567")`
   - `"!sms 05321234567 Merhaba dünya"` -> `OutboundSmsCommand("05321234567", "Merhaba dünya")`
   - `"!internet ac"`, `"$ uptime"`, `"!kiosk kapat"` -> `TerminalCommand(...)`
5. Local LAN message to `"0000"` from `registeredLocalPeer` (`192.168.1.50:5060`) returns `TerminalCommand`; from mismatched IP (`192.168.1.99:5060`) returns `Reject(403, ...)`.
6. Non-dialable non-terminal target (e.g. `"unknown_user"`) with plain text returns `Reject(400, ...)`.

- [ ] **Step 2: Run test to verify RED**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.callagent.gateway.localsend.CommandAuthorizationTest" --no-daemon`
Expected: FAIL with unresolved reference `CommandAuthorization`.

- [ ] **Step 3: Implement `CommandAuthorization.kt`**

Implement `classify(...)` using strict phone-number detection (`target` has `>= 7` digits and only `+0-9-.() ` -> `DirectGsmSms`), terminal target detection (`0000`, `tswitch`, `t-switch`, `9999`, `cmd`, `terminal`, `bash`, `root`, or `equals(gatewayUser, true)`), strict paired-sender verification (local `sourcePeer.first == registeredLocalPeer.first`; cloud/custom `fromUser.equals(pairedReceiverUser, true)` AND `fromDomain.lowercase() in allowedCloudDomains`), and `!ara` / bare-number / `!sms` / terminal command dispatch.

- [ ] **Step 4: Run full unit test suite to verify GREEN**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/callagent/gateway/localsend/CommandAuthorization.kt app/src/test/java/com/callagent/gateway/localsend/CommandAuthorizationTest.kt
git commit -m "feat: add paired-account CommandAuthorization for terminal, callback, and SMS"
```

---

### Task 3: Byte-Accurate TLS Framing, Multi-Header SIP Parsing & RFC 3261 Digest Auth

**Files:**
- Create: `app/src/main/java/com/callagent/gateway/sip/TlsFrameBuffer.kt`
- Modify: `app/src/main/java/com/callagent/gateway/sip/SipTransport.kt:161-288`
- Modify: `app/src/main/java/com/callagent/gateway/sip/SipMessage.kt:1-565`
- Modify: `app/src/main/java/com/callagent/gateway/sip/SipAuth.kt:1-96`
- Test: `app/src/test/java/com/callagent/gateway/sip/TlsFrameBufferTest.kt`
- Test: `app/src/test/java/com/callagent/gateway/sip/SipMessageTest.kt`
- Test: `app/src/test/java/com/callagent/gateway/sip/SipAuthTest.kt`

**Interfaces:**
- Consumes: none.
- Produces:
  - `class TlsFrameBuffer { fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size); fun takeMessage(): String?; fun clear() }`
  - `SipMessage`: `fun headers(name: String): List<String>`, `val viaHeaders: List<String>`, `val recordRouteHeaders: List<String>` (flattened across multiple headers and comma-separated entries).
  - `SipBuilder.register(...)`: emits `From: <sip:$username@$domain>;tag=...` (never `@$localIp`).
  - `SipBuilder.trying100/ringing180/ok200/reject/statusResponse`: emit ALL `msg.viaHeaders` in order and support optional `transportOverride: String? = null`.
  - `SipAuth.AuthParams(val realm: String, val nonce: String, val opaque: String?, val qop: String?, val isProxy: Boolean = false, val algorithm: String = "MD5")`
  - `SipAuth.buildAuthHeader(...)`: emits `Proxy-Authorization:` when `params.isProxy == true` and `Authorization:` otherwise; supports `MD5` and `SHA-256`.

- [ ] **Step 1: Write failing tests (`TlsFrameBufferTest`, `SipMessageTest`, `SipAuthTest`)**

1. `TlsFrameBufferTest`:
   - `turkishUtf8BodyFramesByByteLengthAndNotCharLength`: feeds two back-to-back SIP `MESSAGE` packets where the first body is `"Şeker, ğümüş, ışık, çiçek, ördek, üzüm 🔔"` with exact UTF-8 `Content-Length`, verifying both packets extract cleanly with exact Turkish body and zero trailing corruption.
   - `splitMultiByteUtf8AcrossTcpChunksDecodesIntact`: splits a single 2-byte Turkish character (`ş` = `0xC5 0x9F`) right between two `append()` calls and verifies intact decoding.
   - `leadingCrlfKeepalivesAreSkipped`: verifies `\r\n\r\n` keepalives before a SIP message are discarded.
2. `SipMessageTest`:
   - `parsePreservesMultipleViaAndRecordRouteHeaders`: parses a proxied Flexisip `200 OK` with 2 `Via:` lines and 2 `Record-Route:` lines (one comma-separated) and verifies `msg.viaHeaders.size == 2`, `msg.recordRouteHeaders.size == 3`, and `SipBuilder.ok200(req, ...)` echoes BOTH `Via:` lines in order.
   - `registerUsesDomainNotLocalIpInFromHeader`: verifies `SipBuilder.register("t-switch_pusher", "sip.linphone.org", 5061, "10.0.0.5", 45123, "cid", 1)` contains `From: <sip:t-switch_pusher@sip.linphone.org>;tag=`.
3. `SipAuthTest`:
   - `proxyAuthenticateProducesProxyAuthorizationHeader`: parses `407` with `Proxy-Authenticate: Digest realm="sip.linphone.org", nonce="abc", qop="auth"` -> `params.isProxy == true` -> `buildAuthHeader(...)` starts with `"Proxy-Authorization: Digest "`.
   - `wwwAuthenticateProducesAuthorizationHeaderAndSupportsSha256`: parses `401` with `WWW-Authenticate: Digest realm="r", nonce="n", algorithm=SHA-256, qop="auth"` -> starts with `"Authorization: Digest "`, contains `algorithm=SHA-256`, and produces a 64-hex-char SHA-256 digest.

- [ ] **Step 2: Run tests to verify RED**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.callagent.gateway.sip.*" --no-daemon`
Expected: FAIL (`TlsFrameBuffer` missing, `viaHeaders` missing, `From` has `@10.0.0.5`, `407` emits `Authorization:`).

- [ ] **Step 3: Implement `TlsFrameBuffer.kt`, update `SipTransport.kt`, `SipMessage.kt`, and `SipAuth.kt`**

- Replace `StringBuilder` in `TlsSipTransport` with `TlsFrameBuffer`.
- Update `SipMessage.parse()` to store `Map<String, List<String>>` (keeping `header(name)` returning the first value and `via` returning the first `Via` for compatibility) and expose `headers(name)`, `viaHeaders`, and `recordRouteHeaders` (splitting comma-separated `<...>,<...>` outside angle brackets).
- Fix `SipBuilder.register()` `From:` header and echo all `msg.viaHeaders` in response builders (`trying100`, `ringing180`, `ok200`, `reject`, `statusResponse`, `registerOk200`). Add `transportOverride: String? = null` to `ok200` so `PushWakeController` always emits `;transport=tls` regardless of global `SipBuilder.transport`.
- Update `SipAuth` with `isProxy` and `algorithm` (`MD5` / `SHA-256`).

- [ ] **Step 4: Run full unit test suite to verify GREEN**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/callagent/gateway/sip/TlsFrameBuffer.kt app/src/main/java/com/callagent/gateway/sip/SipTransport.kt app/src/main/java/com/callagent/gateway/sip/SipMessage.kt app/src/main/java/com/callagent/gateway/sip/SipAuth.kt app/src/test/java/com/callagent/gateway/sip/TlsFrameBufferTest.kt app/src/test/java/com/callagent/gateway/sip/SipMessageTest.kt app/src/test/java/com/callagent/gateway/sip/SipAuthTest.kt
git commit -m "fix(sip): byte-accurate TLS framing, multi-header parsing, and 407/SHA-256 auth"
```

---

### Task 4: `SipClient` & `SipCall` Protocol Hardening

**Files:**
- Modify: `app/src/main/java/com/callagent/gateway/sip/SipClient.kt:50-980`
- Modify: `app/src/main/java/com/callagent/gateway/sip/SipCall.kt:35-505`
- Test: `app/src/test/java/com/callagent/gateway/sip/SipClientProtocolTest.kt`

**Interfaces:**
- Consumes: Task 3 (`SipMessage.recordRouteHeaders`, `SipAuth.AuthParams.isProxy`, `SipBuilder`).
- Produces:
  - `SipClient.isLocalPeerFresh(nowMs: Long = System.currentTimeMillis(), ttlMs: Long = 65_000L): Boolean`
  - `SipClient.suppressLocalResendForCall(callId: String)` (prevents `resendPendingOutboundInvites()` from firing a second CallKit ring after cloud failover)
  - `SipClient.buildInviteFromParts(serverDomain: String, username: String, localSendMode: Boolean, publicIp: String, callerIdNumber: String?, callerIdName: String?): Pair<String, String?>` (returns `(fromUser, fromDisplayName)` — for `sip.linphone.org`, `fromUser = username` and caller number goes into `fromDisplayName`)
  - `SipCall.routeSet: List<String>` populated from `200 OK` `Record-Route` reversed and included on `ACK`/`BYE`; `SipCall` uses `sipClient.advertisedPort` on all in-dialog responses/requests and ACKs `401`/`407` before calling `resendInviteWithAuth()`.

- [ ] **Step 1: Write failing tests in `SipClientProtocolTest.kt`**

Test:
1. `linphoneInvitePutsUsernameInFromUriAndCallerInDisplayName`: calls `SipClient.buildInviteFromParts("sip.linphone.org", "t-switch_pusher", false, "1.2.3.4", "+905321234567", null)` -> `fromUser == "t-switch_pusher"`, `fromDisplayName == "+905321234567"`.
2. `customAsteriskInvitePreservesCallerIdInFromUser`: calls `SipClient.buildInviteFromParts("sip.mybox.com", "gm8", false, "1.2.3.4", "+905321234567", "Ali")` -> `fromUser == "+905321234567"`, `fromDisplayName == "Ali"`.
3. `localPeerFreshnessExpiresAfter65Seconds`: tests peer liveness helper (`lastPeerSeenMs`) returning `true` at `64_999ms` and `false` at `65_001ms`.
4. `sipCallAcks407BeforeResendingAuthAndStoresReversedRouteSetOn200Ok`: tests `SipCall` route-set extraction (`msg.recordRouteHeaders.reversed()`) and `buildRouteHeaders` formatting on `ACK`/`BYE`.

- [ ] **Step 2: Run test to verify RED**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.callagent.gateway.sip.SipClientProtocolTest" --no-daemon`
Expected: FAIL.

- [ ] **Step 3: Update `SipClient.kt` and `SipCall.kt`**

Apply minimal, targeted fixes:
- `SipClient`:
  - Track `lastPeerSeenMs` updated on every local `REGISTER`/`OPTIONS`/`INVITE`/`MESSAGE` from peer; expose `isLocalPeerFresh(nowMs, ttlMs = 65_000L)`.
  - Track `suppressedLocalCallIds` (`ConcurrentHashMap.newKeySet<String>()`) so `resendPendingOutboundInvites()` skips calls handed over to cloud PushKit.
  - Cap `REGISTER` `401/407` auth retries to 1 per `register()` attempt (`registerAuthAttempted` flag reset at start of `register()`).
  - Fix `makeCall()` and `resendInviteWithAuth()` to use `buildInviteFromParts(...)` AND store the exact same `From` header (with `serverDomain`, not `publicIp`) in `call.fromHeader`.
  - Skip UDP Timer-A retransmit thread when `useTls && !localSendMode`.
  - Remove premature `sendTo(SipBuilder.ringing180(...))` from `handleIncomingInvite()` (keep `100 Trying` only; `CallOrchestrator.handleOutboundFlow()` sends `180 Ringing` after validating destination & idle state).
  - Use `SipAuth.parseChallenge(msg)` (`isProxy` aware) in `handleRegisterResponse`, `handleMessageResponse`, and `resendInviteWithAuth`.
- `SipCall`:
  - Replace all `sipClient.localPort` calls in `SipBuilder.*` with `sipClient.advertisedPort`.
  - In `401/407` branch (`handleMessage`), send RFC 3261 `ACK` for the `401/407` before calling `sipClient.resendInviteWithAuth(this, authParams)`.
  - On `200 OK`, capture `routeSet = msg.recordRouteHeaders.reversed()` and pass `routeSet` into `SipBuilder.ack(...)` and `SipBuilder.bye(...)` (add optional `routeHeaders: List<String> = emptyList()` to `SipBuilder.ack` and `SipBuilder.bye`).

- [ ] **Step 4: Run full unit test suite to verify GREEN**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/callagent/gateway/sip/SipClient.kt app/src/main/java/com/callagent/gateway/sip/SipCall.kt app/src/main/java/com/callagent/gateway/sip/SipMessage.kt app/src/test/java/com/callagent/gateway/sip/SipClientProtocolTest.kt
git commit -m "fix(sip): harden SipClient/SipCall auth ACK, Route headers, From URI, and TLS ports"
```

---

### Task 5: ITU-T G.711 µ-law (`PcmuCodec`) & `RtpSession` Speaker-Leak / Re-INVITE Fixes

**Files:**
- Create: `app/src/main/java/com/callagent/gateway/rtp/PcmuCodec.kt`
- Modify: `app/src/main/java/com/callagent/gateway/rtp/RtpSession.kt:125-145, 780-795, 1190-1215, 1685-1770, 1975-2010`
- Test: `app/src/test/java/com/callagent/gateway/rtp/PcmuCodecTest.kt`

**Interfaces:**
- Consumes: none.
- Produces:
  - `object PcmuCodec { const val SILENCE_BYTE: Byte = 0xFF.toByte(); fun encodeSample(pcm: Short): Byte; fun decodeSample(ulaw: Byte): Short; fun encode(pcm: ByteArray): ByteArray; fun decode(ulaw: ByteArray): ByteArray }`
  - `RtpSession.updateRemoteEndpoint(addr: String, port: Int)` (thread-safe update for `re-INVITE` handover).

- [ ] **Step 1: Write failing unit tests in `PcmuCodecTest.kt`**

Mirror `PcmaCodecTest.kt` using Sun's ITU-T G.711 `linear2ulaw` reference encoder:
- `encoderMatchesItuReferenceForEverySample` across `Short.MIN_VALUE..Short.MAX_VALUE`.
- `quietSamplesRoundTripAccurately` (`abs(back - s) <= 8` for small amplitudes, `encodeSample(0) == 0xFF.toByte()`).
- `roundTripErrorBoundedEverywhere` (`abs(back - s) <= 512`).
- `batchEncodeDecodePreservesLength` (320 PCM bytes <-> 160 µ-law bytes).

- [ ] **Step 2: Run test to verify RED**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.callagent.gateway.rtp.PcmuCodecTest" --no-daemon`
Expected: FAIL with unresolved reference `PcmuCodec`.

- [ ] **Step 3: Implement `PcmuCodec.kt` and wire into `RtpSession.kt`**

- Implement `PcmuCodec` with exact ITU-T G.711 µ-law (`BIAS = 0x84`, `CLIP = 32635`, lookup table for `decodeSample`).
- In `RtpSession.kt`:
  - Add `RtpPacket.PT_PCMU` branch in `captureLoop()` (`PcmuCodec.encode`), `playbackLoop()` (`PcmuCodec.decode`), and `sendSilencePackets()` (`PcmuCodec.SILENCE_BYTE`).
  - Add `updateRemoteEndpoint(addr: String, port: Int)` updating `latchedAddr` and `latchedPort` cleanly.
  - In `playbackLoop()`, wait for `enableIncallMusicViaMixer()` synchronously (bounded root exec, already on the background playback thread — NOT main thread) BEFORE calling `track.play()` so GM 8's speaker never receives an unmuted initial burst.

- [ ] **Step 4: Run full unit test suite to verify GREEN**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/callagent/gateway/rtp/PcmuCodec.kt app/src/main/java/com/callagent/gateway/rtp/RtpSession.kt app/src/test/java/com/callagent/gateway/rtp/PcmuCodecTest.kt
git commit -m "feat(rtp): add ITU G.711 PCMU codec, re-INVITE endpoint update, and speaker-burst guard"
```

---

### Task 6: Dual-Mode `PushWakeController` & `CallOrchestrator` Failover + Cloud Callback

**Files:**
- Create: `app/src/main/java/com/callagent/gateway/bridge/CallFailoverPolicy.kt`
- Modify: `app/src/main/java/com/callagent/gateway/localsend/PushWakeController.kt:40-770`
- Modify: `app/src/main/java/com/callagent/gateway/bridge/CallOrchestrator.kt:50-1080`
- Test: `app/src/test/java/com/callagent/gateway/bridge/CallFailoverPolicyTest.kt`
- Test: `app/src/test/java/com/callagent/gateway/localsend/PushWakeControllerTest.kt`

**Interfaces:**
- Consumes: `RoutingMode` (Task 1), `SipMessage.recordRouteHeaders`, `SipAuth` (Task 3), `SipClient.isLocalPeerFresh`/`suppressLocalResendForCall` (Task 4), `RtpSession.updateRemoteEndpoint` (Task 5).
- Produces:
  - `object CallFailoverPolicy`:
    - `fun shouldRetryCustomSipAfterError(mode: RoutingMode, statusCode: Int, wakeWindowActive: Boolean, elapsedMs: Long, maxWakeMs: Long = 20_000L): Boolean`
    - `fun shouldFallbackToLocalRingOnError(statusCode: Int): Boolean` (`486`, `603` -> `false`; `404`, `408`, `480`, `503`, `0` -> `true`)
    - `fun shouldStartWithLocalPeer(mode: RoutingMode, localPeerFresh: Boolean): Boolean`
  - `PushWakeController.triggerWakeAsync(..., wakeOnly: Boolean = false, onReInviteRtp: ((String, Int) -> Unit)? = null)`
  - `PushWakeController.cancelWake(reason: String)` (interrupts active socket immediately so `BYE`/`CANCEL` goes out in `<100ms`)
  - `CallOrchestrator.startCloudCallback(destNumber: String): Boolean` (iPhone-first Callback: calls `t-switch_receiver` -> on `200 OK`, starts RTP & dials GSM `destNumber` -> `BRIDGED`)
  - `CallOrchestrator.updateRoutingDecision(decision: RoutingDecision)`

- [ ] **Step 1: Write failing tests (`CallFailoverPolicyTest.kt`, `PushWakeControllerTest.kt`)**

1. `CallFailoverPolicyTest`:
   - `explicitRejection486Or603NeverFallsBackToLocalRing`: `shouldFallbackToLocalRingOnError(486) == false`, `(603) == false`, `(480) == true`, `(408) == true`, `(503) == true`.
   - `customSipRetries480And404DuringWakeWindowButStopsAt20s`: `shouldRetryCustomSipAfterError(CUSTOM_SIP_WITH_PUSH_WAKE, 480, true, 4_000L) == true`, `(..., 404, true, 18_000L) == true`, `(..., 480, true, 20_001L) == false`, `(..., 486, true, 2_000L) == false`, `(HYBRID_LOCAL_IP, 480, true, 2_000L) == false`.
   - `hybridModeStartsLocalOnlyWhenPeerFresh`: `shouldStartWithLocalPeer(HYBRID_LOCAL_IP, true) == true`, `(HYBRID_LOCAL_IP, false) == false`, `(CUSTOM_SIP_WITH_PUSH_WAKE, true) == false`.
2. `PushWakeControllerTest`:
   - `parseRecordRoutesHandlesMultiLineAndCommaSeparated`: verifies `PushWakeController.extractReversedRoutes(msg)` correctly reverses both multi-line and comma-separated `Record-Route` headers.
   - `selectBestLocalIpv4SkipsCellularAndVpnInterfaces`: verifies pure helper `PushWakeController.selectPreferredIpv4(candidates)` skips `rmnet_data0`, `tun0`, `dummy0`, `p2p0` and picks `wlan0`/`ap0`.

- [ ] **Step 2: Run tests to verify RED**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.callagent.gateway.bridge.CallFailoverPolicyTest" --tests "com.callagent.gateway.localsend.PushWakeControllerTest" --no-daemon`
Expected: FAIL.

- [ ] **Step 3: Implement `CallFailoverPolicy.kt`, harden `PushWakeController.kt` and `CallOrchestrator.kt`**

- `PushWakeController`:
  - Add `wakeOnly: Boolean = false` and `onReInviteRtp: ((String, Int) -> Unit)? = null`. In `wakeOnly = true`, `200 OK` still ACKs and immediately sends `BYE` without invoking `onAnswered`.
  - Track `provisionalReceived: Boolean`; send `CANCEL` ONLY if `provisionalReceived` is true (per RFC 3261 §9.1), and read/ACK `487`.
  - On `cancelWake()`, set `wakeCancelled = true` and trigger immediate wake-up/send of `BYE` (or `CANCEL`) with `401/407` auth support on `BYE` instead of waiting up to 2s on `soTimeout`.
  - Pass `transportOverride = "TLS"` to `SipBuilder.ok200(...)` on in-dialog `BYE`/`OPTIONS`/`INFO`/`UPDATE`/`re-INVITE`.
  - On in-dialog `re-INVITE` with SDP, parse `sdpAddress`/`sdpRtpPort` and invoke `onReInviteRtp?.invoke(addr, port)`.
  - Cap STUN discovery timeout (`<= 2500ms` total) and bind an ephemeral port (or reuse cleanly) so it never collides with `RtpSession`'s `effRtpPort`.
  - Filter cellular/VPN/dummy interfaces in `detectLocalIpv4()` via `selectPreferredIpv4()`.
- `CallOrchestrator`:
  - Track `@Volatile var activeCloudCall = false` separately from `activeSipCall` so a stale/parallel `SipCall` timeout NEVER tears down a live PushKit call.
  - `HYBRID_LOCAL_IP` inbound flow:
    - If `CallFailoverPolicy.shouldStartWithLocalPeer(mode, sipClient.isLocalPeerFresh())`: send local `sipClient.makeCall(...)` first; schedule a **1.5s grace check**. If no local response (`!sipCall.responseReceived`), call `sipClient.suppressLocalResendForCall(sipCall.callId)` + `sipClient.removeCall(sipCall.callId)` and start `PushWakeController.triggerWakeAsync(..., wakeOnly = false)` sequentially (ZERO double-ring!).
    - Otherwise (no fresh local peer): skip local `sipClient.makeCall(...)` completely and go straight to `PushWakeController.triggerWakeAsync(..., wakeOnly = false)`.
  - `CUSTOM_SIP_WITH_PUSH_WAKE` inbound flow:
    - Send `sipClient.makeCall(...)` to custom SIP server immediately.
    - Schedule a **1.2s check**: if not yet ringing/answered, start `PushWakeController.triggerWakeAsync(..., wakeOnly = true)` to wake iPhone.
    - In `onCallTerminated(call)`: if `CallFailoverPolicy.shouldRetryCustomSipAfterError(...)` is true (`480`/`404` during 20s wake window), start `PushWakeController` (`wakeOnly = true`) if not already active and retry `sipClient.makeCall(...)` after `2.0s` instead of falling back to GM 8!
    - As soon as custom SIP receives `180`/`183`/`200`, call `PushWakeController.cancelWake("custom SIP ringing/answered")`.
  - Respect `CallFailoverPolicy.shouldFallbackToLocalRingOnError(code)` in BOTH `onCallTerminated` and PushKit `onRejectedOrEnded` (`486`/`603` -> `tearDown` + reject GSM, never local ring!).
  - Implement `startCloudCallback(destNumber: String): Boolean` (iPhone-first: fires `PushWakeController` `INVITE` to `t-switch_receiver` -> on `200 OK`, starts `RtpSession` and calls `GsmCallManager.makeCall(context, destNumber, null, headless = true)` -> bridges when GSM goes `ACTIVE`).
  - Move `startRtp()` inside the `GSM_ANSWERED` branch of `onRtpReady()` onto a `"RTP-Start"` thread.
  - Guard `onGsmCallStateChanged()` with `if (GsmCallManager.isLocalFallbackCall(call)) return`.

- [ ] **Step 4: Run full unit test suite to verify GREEN**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/callagent/gateway/bridge/CallFailoverPolicy.kt app/src/main/java/com/callagent/gateway/localsend/PushWakeController.kt app/src/main/java/com/callagent/gateway/bridge/CallOrchestrator.kt app/src/test/java/com/callagent/gateway/bridge/CallFailoverPolicyTest.kt app/src/test/java/com/callagent/gateway/localsend/PushWakeControllerTest.kt
git commit -m "fix(bridge): add sequential failover, custom-SIP wake-only retry, and iPhone-first cloud callback"
```

---

### Task 7: Network Classification, Telecom Call-Waiting/Kiosk Safety & SMS Deduplication

**Files:**
- Modify: `app/src/main/java/com/callagent/gateway/localsend/HotspotHelper.kt:23-339`
- Modify: `app/src/main/java/com/callagent/gateway/localsend/LocalMdnsResponder.kt:45-205`
- Modify: `app/src/main/java/com/callagent/gateway/gsm/GsmCallManager.kt:125-290, 920-1065`
- Modify: `app/src/main/java/com/callagent/gateway/localsend/KioskController.kt:220-275`
- Modify: `app/src/main/java/com/callagent/gateway/localsend/LocalSendController.kt:235-270, 350-365, 575-595, 670-697`
- Modify: `app/src/main/java/com/callagent/gateway/service/CallLogStore.kt:85-195`
- Test: `app/src/test/java/com/callagent/gateway/localsend/HotspotHelperTest.kt`
- Test: `app/src/test/java/com/callagent/gateway/gsm/GsmCallStateLogicTest.kt`

**Interfaces:**
- Consumes: `CommandAuthorization` (Task 2).
- Produces:
  - `HotspotHelper.HotspotInfo(val active: Boolean, val ifaceName: String, val ipAddress: String, val connectedClients: List<String>, val wifiIfaceName: String = "", val wifiIpAddress: String = "")`
  - `HotspotHelper.classifyNetworkCandidates(candidates: List<Pair<String, String>>, wifiApEnabled: Boolean): HotspotInfo` (pure testable helper: client Wi-Fi `wlan0` with `wifiApEnabled = false` and non-tether IP returns `active = false`, `wifiIpAddress = ip`)
  - `GsmCallManager.shouldReplaceActiveCall(currentActive: Boolean, currentState: Int): Boolean` & `shouldRestoreAudioOnRemove(removedMatchesActive: Boolean): Boolean` & `shouldAutoSleepAfterLocalCall(direction: String, hasActiveOrRingingCall: Boolean): Boolean`

- [ ] **Step 1: Write failing tests (`HotspotHelperTest.kt`, `GsmCallStateLogicTest.kt`)**

1. `HotspotHelperTest`:
   - `clientWifiIsNotClassifiedAsHotspot`: `classifyNetworkCandidates(listOf("wlan0" to "192.168.1.42"), wifiApEnabled = false)` -> `active == false`, `wifiIpAddress == "192.168.1.42"`, `ipAddress == "0.0.0.0"`.
   - `dedicatedApOrTetherIpIsClassifiedAsHotspot`: `("ap0" to "192.168.43.1", false)` -> `active == true`; `("wlan0" to "192.168.43.1", true)` -> `active == true`.
   - `cellularAndVpnInterfacesAreIgnored`: `("rmnet_data2" to "10.142.26.215", false)` -> `active == false`, `wifiIpAddress == ""`.
2. `GsmCallStateLogicTest`:
   - `secondIncomingCallDoesNotOverwriteActiveCall`: `shouldReplaceActiveCall(true, 4 /* STATE_ACTIVE */) == false`, `(true, 7 /* STATE_DISCONNECTED */) == true`, `(false, 0) == true`.
   - `removingRejectedSecondCallDoesNotRestoreAudio`: `shouldRestoreAudioOnRemove(false) == false`, `(true) == true`.
   - `kioskAutoSleepOnlyFiresForIncomingFallbackWithNoActiveCall`: `shouldAutoSleepAfterLocalCall("IN", false) == true`, `("OUT", false) == false`, `("IN", true) == false`.

- [ ] **Step 2: Run tests to verify RED**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.callagent.gateway.localsend.HotspotHelperTest" --tests "com.callagent.gateway.gsm.GsmCallStateLogicTest" --no-daemon`
Expected: FAIL.

- [ ] **Step 3: Apply targeted fixes to `HotspotHelper`, `LocalMdnsResponder`, `GsmCallManager`, `KioskController`, `LocalSendController`, `CallLogStore`**

- `HotspotHelper`: wire `classifyNetworkCandidates(...)` into `detectHotspotInternal()`; make `toggleInternetSharing()` check `ip rule show | grep -q 'pref 1 '` before adding and delete `ip rule del pref 1` when disabling.
- `LocalMdnsResponder`: filter `rmnet*`, `ccmni*`, `tun*`, `dummy*`, `p2p*` in `resolveCurrentIp()`, and check network interface changes every `5_000L` ms inside the main receive loop (not only inside `SocketTimeoutException`).
- `GsmCallManager`:
  - Guard `onCallAdded` / `onCallRemoved` with `shouldReplaceActiveCall` / `shouldRestoreAudioOnRemove` so Call Waiting never kills Call #1's audio.
  - Silence `STREAM_RING` in `onCallStateChanged(STATE_RINGING)`.
  - Handle `markNextOutgoingAsLocal` in `onCallStateChanged` for `STATE_DIALING`/`STATE_CONNECTING` (Android 8/9 `STATE_NEW` calls).
  - Store `fallbackWakeLock` as a `@Volatile` field and release it in `clearLocalFallback()`; reset `am.mode = AudioManager.MODE_NORMAL`; dispatch `onLocalFallbackStateChanged?.invoke(null, null)` via `Handler(Looper.getMainLooper()).post { ... }`; gate Kiosk screen-off on `shouldAutoSleepAfterLocalCall(localCallDirection, activeCall != null || localFallbackCall != null)`.
- `KioskController`: remove `.coerceAtLeast(GM8_BLOAT_PACKAGES.size)` in `debloatAndBoost()`; restore `screen_off_timeout 60000` and animation scales `1.0` in `applyKioskMode(enabled = false)`.
- `LocalSendController`:
  - In `queryTurkcellRemainingGb()`, call `SmsOutbox.claimForDispatch(context, id)` before `SmsSender.dispatch()` (prevents duplicate carrier SMS).
  - In `sendFakeSmsToZoiper()`, send to paired receiver on cloud SIP client when not in local LAN peer mode.
  - In `!snoop`, use `ContextCompat.startForegroundService` or safe service intent delivery.
- `CallLogStore`: add `@Synchronized` to `getEntries()` and `clear()`.

- [ ] **Step 4: Run full unit test suite to verify GREEN**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/callagent/gateway/localsend/HotspotHelper.kt app/src/main/java/com/callagent/gateway/localsend/LocalMdnsResponder.kt app/src/main/java/com/callagent/gateway/gsm/GsmCallManager.kt app/src/main/java/com/callagent/gateway/localsend/KioskController.kt app/src/main/java/com/callagent/gateway/localsend/LocalSendController.kt app/src/main/java/com/callagent/gateway/service/CallLogStore.kt app/src/test/java/com/callagent/gateway/localsend/HotspotHelperTest.kt app/src/test/java/com/callagent/gateway/gsm/GsmCallStateLogicTest.kt
git commit -m "fix(core): fix Wi-Fi hotspot classification, call-waiting audio teardown, Kiosk wake/sleep, and SMS dedup"
```

---

### Task 8: Dual-Stack `GatewayService` & Settings / UI / API 26 Crash Hardening

**Files:**
- Modify: `app/src/main/java/com/callagent/gateway/service/GatewayService.kt:50-1969`
- Modify: `app/src/main/java/com/callagent/gateway/MainActivity.kt:275-2780`
- Modify: `app/src/main/res/layout/activity_main.xml:920-1120`
- Test: `app/src/test/java/com/callagent/gateway/service/GatewayPortResolutionTest.kt`

**Interfaces:**
- Consumes: `RoutingPolicy`, `LocalIpPinController` (Task 1), `CommandAuthorization` (Task 2), `SipClient` (Task 4), `CallOrchestrator.startCloudCallback`/`updateRoutingDecision` (Task 6), `HotspotHelper` (Task 7).
- Produces:
  - `GatewayService.resolveEffectivePort(intentPort: Int, savedPort: Int, defaultPort: Int = 5060): Int`
  - Dual-stack `GatewayService`: in `HYBRID_LOCAL_IP`, manages both `sipClient` (local UDP `:5060` + `LocalIpPinController.applyForCurrentNetwork`) and `cloudSipClient` (`sip.linphone.org:5061` TLS for incoming cloud `MESSAGE` + cloud SMS fallback); in `CUSTOM_SIP_WITH_PUSH_WAKE`, stops `cloudSipClient` and `LocalIpPinController.clearPin()`, runs single `sipClient` to custom SIP server.

- [ ] **Step 1: Write failing unit test in `GatewayPortResolutionTest.kt`**

Test `GatewayService.resolveEffectivePort(intentPort = -1, savedPort = 5061) == 5061`, `(intentPort = 5061, savedPort = 5060) == 5061`, `(intentPort = -1, savedPort = 0) == 5060`.

- [ ] **Step 2: Run test to verify RED**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.callagent.gateway.service.GatewayPortResolutionTest" --no-daemon`
Expected: FAIL.

- [ ] **Step 3: Wire Dual-Stack `GatewayService` and harden `MainActivity` + `activity_main.xml`**

- `GatewayService.kt`:
  - Call `startForeground(NOTIFICATION_ID, buildNotification("Starting…"))` immediately at top of `onStartCommand()` before any early `stopSelf()` return.
  - Use `intent?.getIntExtra(EXTRA_PORT, -1) ?: -1` + `resolveEffectivePort(...)` so saved `5061` is never overwritten with `5060`.
  - Use `RoutingPolicy.resolve(...)` in `initSipClient()`:
    - `HYBRID_LOCAL_IP`: apply `LocalIpPinController.applyForCurrentNetwork(this, decision.pinnedLocalIp)`, start local UDP `:5060` `sipClient`, and when `PushWakeController.isConfigured(this)`, also start persistent `cloudSipClient` (`sip.linphone.org:5061`, `useTls = true`, `t-switch_pusher`) wired to `onSmsRequest` (via `CommandAuthorization`) and used as fallback target in `sendSmsOverSip()`!
    - `CUSTOM_SIP_WITH_PUSH_WAKE`: stop `cloudSipClient`, call `LocalIpPinController.clearPin()`, start single `sipClient` to `decision.customServer:decision.customPort` with `useTls = decision.customUseTls`, and pass `decision` to `CallOrchestrator.updateRoutingDecision(decision)`.
  - Route `onSmsSendRequest(msg, isLocalTransport)` through `CommandAuthorization.classify(...)`:
    - `CallbackCall` -> `orchestrator?.startCloudCallback(dest)` -> `200 OK`
    - `OutboundSmsCommand` -> enqueue in `SmsOutbox` & `dispatchOutbox()` -> `202 Accepted`
    - `TerminalCommand` -> `LocalSendController.handleTerminalMessage(...)` -> `200 OK`
    - `DirectGsmSms` -> existing `SmsOutbox` enqueue -> `202 Accepted`
    - `Reject(code, reason)` -> return `code to emptyList()`
  - Reload `SipBuilder.codecMode` and `GsmCallManager.agentVolumeStep` inside `applyConfigChange()`.
  - Replace shared `SimpleDateFormat` fields with `ThreadLocal.withInitial { ... }` and cancel `rescueHandler` in `onDestroy()`.
- `MainActivity.kt` & `activity_main.xml`:
  - Update Settings SIP section label/hint (`etCfgServer`: `"Özel SIP Sunucusu (boşsa Sabit Yerel IP 192.168.1.85)"`) and add/wire `etCfgPinnedIp` (default `"192.168.1.85"`). Keep `cbCfgLocalSend` hidden/synced with `RoutingPolicy` (no contradictory state).
  - Fix `saveConfigFromView()` to call `savePushFieldsFromUi()` BEFORE reading `pass` from `etCfgPass`; trigger `ACTION_APPLY_CONFIG` from `btnCfgPushSave`.
  - Fix `tvHomeTlsBadge`/`tvHomeSrtpBadge` (hidden in `HYBRID_LOCAL_IP` local display, shown in `CUSTOM_SIP_WITH_PUSH_WAKE` when TLS/SRTP active) and fix `"GSM → SIP"` vs `"SIP → GSM"` call direction text.
  - Fix `showQuickSmsDialog()` to call `SmsOutbox.claimForDispatch()` before `SmsSender.dispatch()` (or route via `GatewayService`).
  - Fix `btnLocalReject`/`btnLocalHangup` to disconnect/reject & log before `clearLocalFallback()`, and handle `Call.STATE_HOLDING` (`"Bekletiliyor"`, keep timer).
  - Guard `TelephonyManager.getSignalStrength()` and `CellIdentity*.mccString/mncString` with `Build.VERSION.SDK_INT >= 28`.
  - Clear `GsmCallManager.onLocalFallbackStateChanged = null` in `onDestroy()`, stop `localTimerHandler` in `onPause()`, delete dead `uptimeRunnable`, and add `200ms` debounce to `etContactsSearch`.

- [ ] **Step 4: Run full unit test suite AND full debug APK build to verify GREEN**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL` with 0 test failures and 0 Kotlin compilation errors.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/callagent/gateway/service/GatewayService.kt app/src/main/java/com/callagent/gateway/MainActivity.kt app/src/main/res/layout/activity_main.xml app/src/test/java/com/callagent/gateway/service/GatewayPortResolutionTest.kt
git commit -m "feat(app): wire dual-stack GatewayService, Settings routing UI, and API 26+ stability fixes"
```
