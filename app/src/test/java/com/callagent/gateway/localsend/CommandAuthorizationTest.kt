package com.callagent.gateway.localsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAuthorizationTest {

    private val allowedDomains = setOf("sip.linphone.org", "custom.sip.example")
    private val gatewayUser = "t-switch_pusher"
    private val pairedReceiver = "t-switch_receiver"

    @Test
    fun phoneNumberTargetWithDollarOrBangIsAlwaysDirectGsmSms() {
        val localDollar = CommandAuthorization.classify(
            isLocalTransport = true,
            target = "+905321234567",
            fromUser = null,
            fromDomain = null,
            sourcePeer = "192.168.1.99" to 5060,
            registeredLocalPeer = "192.168.1.50" to 5060,
            pairedReceiverUser = pairedReceiver,
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = "\$50 gönderdim"
        )
        assertEquals(
            InboundMessageAction.DirectGsmSms("+905321234567", "\$50 gönderdim"),
            localDollar
        )

        val cloudBang = CommandAuthorization.classify(
            isLocalTransport = false,
            target = "05321234567",
            fromUser = pairedReceiver,
            fromDomain = "sip.linphone.org",
            sourcePeer = null,
            registeredLocalPeer = null,
            pairedReceiverUser = pairedReceiver,
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = "!acil durum"
        )
        assertEquals(
            InboundMessageAction.DirectGsmSms("05321234567", "!acil durum"),
            cloudBang
        )
    }

    @Test
    fun cloudForeignSenderIsRejected403() {
        val bashAttempt = CommandAuthorization.classify(
            isLocalTransport = false,
            target = gatewayUser,
            fromUser = "attacker",
            fromDomain = "sip.linphone.org",
            sourcePeer = null,
            registeredLocalPeer = null,
            pairedReceiverUser = pairedReceiver,
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = "\$ id"
        )
        assertTrue(bashAttempt is InboundMessageAction.Reject)
        assertEquals(403, (bashAttempt as InboundMessageAction.Reject).statusCode)

        val callbackAttempt = CommandAuthorization.classify(
            isLocalTransport = false,
            target = gatewayUser,
            fromUser = "attacker",
            fromDomain = "sip.linphone.org",
            sourcePeer = null,
            registeredLocalPeer = null,
            pairedReceiverUser = pairedReceiver,
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = "!ara 05321234567"
        )
        assertTrue(callbackAttempt is InboundMessageAction.Reject)
        assertEquals(403, (callbackAttempt as InboundMessageAction.Reject).statusCode)

        val blankPairedReceiver = CommandAuthorization.classify(
            isLocalTransport = false,
            target = "0000",
            fromUser = pairedReceiver,
            fromDomain = "sip.linphone.org",
            sourcePeer = null,
            registeredLocalPeer = null,
            pairedReceiverUser = "",
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = "!gb"
        )
        assertTrue(blankPairedReceiver is InboundMessageAction.Reject)
        assertEquals(403, (blankPairedReceiver as InboundMessageAction.Reject).statusCode)
    }

    @Test
    fun cloudWrongDomainIsRejected403() {
        val wrongDomain = CommandAuthorization.classify(
            isLocalTransport = false,
            target = gatewayUser,
            fromUser = pairedReceiver,
            fromDomain = "evil.com",
            sourcePeer = null,
            registeredLocalPeer = null,
            pairedReceiverUser = pairedReceiver,
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = "\$ id"
        )
        assertTrue(wrongDomain is InboundMessageAction.Reject)
        assertEquals(403, (wrongDomain as InboundMessageAction.Reject).statusCode)

        val nullDomain = CommandAuthorization.classify(
            isLocalTransport = false,
            target = gatewayUser,
            fromUser = pairedReceiver,
            fromDomain = null,
            sourcePeer = null,
            registeredLocalPeer = null,
            pairedReceiverUser = pairedReceiver,
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = "!kiosk kapat"
        )
        assertTrue(nullDomain is InboundMessageAction.Reject)
        assertEquals(403, (nullDomain as InboundMessageAction.Reject).statusCode)
    }

    @Test
    fun cloudPairedReceiverAllowsCallbackSmsAndAllTerminalCommands() {
        fun classifyCloud(body: String, target: String = gatewayUser): InboundMessageAction {
            return CommandAuthorization.classify(
                isLocalTransport = false,
                target = target,
                fromUser = pairedReceiver,
                fromDomain = "sip.linphone.org",
                sourcePeer = null,
                registeredLocalPeer = null,
                pairedReceiverUser = pairedReceiver,
                allowedCloudDomains = allowedDomains,
                gatewayUser = gatewayUser,
                body = body
            )
        }

        assertEquals(
            InboundMessageAction.CallbackCall("+905321234567"),
            classifyCloud("!ara +905321234567")
        )
        assertEquals(
            InboundMessageAction.CallbackCall("+905321234567"),
            classifyCloud("+905321234567")
        )
        assertEquals(
            InboundMessageAction.OutboundSmsCommand("05321234567", "Merhaba dünya"),
            classifyCloud("!sms 05321234567 Merhaba dünya")
        )

        val terminalBodies = listOf(
            "!internet ac",
            "\$ uptime",
            "!kiosk kapat",
            "!gb",
            "!ekran kapat",
            "!debloat",
            "!snoop",
            "!batarya",
            "!geneldurum",
            "!yardim",
            "!uptime"
        )
        for (cmd in terminalBodies) {
            assertEquals(
                "Expected TerminalCommand for '$cmd'",
                InboundMessageAction.TerminalCommand(cmd),
                classifyCloud(cmd)
            )
        }

        val missingSmsText = classifyCloud("!sms 05321234567")
        assertTrue(missingSmsText is InboundMessageAction.Reject)
        assertEquals(400, (missingSmsText as InboundMessageAction.Reject).statusCode)

        val emptyCallback = classifyCloud("!ara")
        assertTrue(emptyCallback is InboundMessageAction.Reject)
        assertEquals(400, (emptyCallback as InboundMessageAction.Reject).statusCode)
    }

    @Test
    fun localLanPeerVerifiedByIp() {
        val allowed = CommandAuthorization.classify(
            isLocalTransport = true,
            target = "0000",
            fromUser = null,
            fromDomain = null,
            sourcePeer = "192.168.1.50" to 54321,
            registeredLocalPeer = "192.168.1.50" to 5060,
            pairedReceiverUser = pairedReceiver,
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = "!gb"
        )
        assertEquals(InboundMessageAction.TerminalCommand("!gb"), allowed)

        val rejectedMismatch = CommandAuthorization.classify(
            isLocalTransport = true,
            target = "0000",
            fromUser = null,
            fromDomain = null,
            sourcePeer = "192.168.1.99" to 5060,
            registeredLocalPeer = "192.168.1.50" to 5060,
            pairedReceiverUser = pairedReceiver,
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = "!gb"
        )
        assertTrue(rejectedMismatch is InboundMessageAction.Reject)
        assertEquals(403, (rejectedMismatch as InboundMessageAction.Reject).statusCode)

        val rejectedNullPeer = CommandAuthorization.classify(
            isLocalTransport = true,
            target = "0000",
            fromUser = null,
            fromDomain = null,
            sourcePeer = "192.168.1.50" to 5060,
            registeredLocalPeer = null,
            pairedReceiverUser = pairedReceiver,
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = "!gb"
        )
        assertTrue(rejectedNullPeer is InboundMessageAction.Reject)
        assertEquals(403, (rejectedNullPeer as InboundMessageAction.Reject).statusCode)
    }

    @Test
    fun nonDialableNonTerminalTargetReturns400() {
        val result = CommandAuthorization.classify(
            isLocalTransport = true,
            target = "random_user",
            fromUser = null,
            fromDomain = null,
            sourcePeer = "192.168.1.50" to 5060,
            registeredLocalPeer = "192.168.1.50" to 5060,
            pairedReceiverUser = pairedReceiver,
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = "hello"
        )
        assertTrue(result is InboundMessageAction.Reject)
        assertEquals(400, (result as InboundMessageAction.Reject).statusCode)
    }

    @Test
    fun emptyBodyReturns400() {
        for (empty in listOf("", "   ", "\n\t")) {
            val result = CommandAuthorization.classify(
                isLocalTransport = true,
                target = "0000",
                fromUser = null,
                fromDomain = null,
                sourcePeer = "192.168.1.50" to 5060,
                registeredLocalPeer = "192.168.1.50" to 5060,
                pairedReceiverUser = pairedReceiver,
                allowedCloudDomains = allowedDomains,
                gatewayUser = gatewayUser,
                body = empty
            )
            assertTrue("Expected Reject for blank body", result is InboundMessageAction.Reject)
            assertEquals(400, (result as InboundMessageAction.Reject).statusCode)
        }
    }

    @Test
    fun isPhoneNumberValidatesDigitsAndAllowedPunctuation() {
        assertTrue(CommandAuthorization.isPhoneNumber("+905321234567"))
        assertTrue(CommandAuthorization.isPhoneNumber("05321234567"))
        assertTrue(CommandAuthorization.isPhoneNumber(" 1234567 "))
        assertTrue(CommandAuthorization.isPhoneNumber("+1 (555) 123-4567"))
        assertTrue(CommandAuthorization.isPhoneNumber("0532.123.4567"))

        assertFalse(CommandAuthorization.isPhoneNumber("0000"))
        assertFalse(CommandAuthorization.isPhoneNumber("9999"))
        assertFalse(CommandAuthorization.isPhoneNumber("123456"))
        assertFalse(CommandAuthorization.isPhoneNumber("tswitch"))
        assertFalse(CommandAuthorization.isPhoneNumber("1234567abc"))
        assertFalse(CommandAuthorization.isPhoneNumber(""))
        assertFalse(CommandAuthorization.isPhoneNumber("   "))
    }

    @Test
    fun spacedBangCommandsAndContactNameQueriesAreClassifiedProperly() {
        fun classifyCloud(body: String) = CommandAuthorization.classify(
            isLocalTransport = false,
            target = gatewayUser,
            fromUser = pairedReceiver,
            fromDomain = "sip.linphone.org",
            sourcePeer = null,
            registeredLocalPeer = null,
            pairedReceiverUser = pairedReceiver,
            allowedCloudDomains = allowedDomains,
            gatewayUser = gatewayUser,
            body = body
        )

        assertEquals(
            InboundMessageAction.CallbackCall("+905321234567"),
            classifyCloud("! ara +905321234567")
        )
        assertEquals(
            InboundMessageAction.ContactCallQuery("Ahmet Yılmaz"),
            classifyCloud("! ara Ahmet Yılmaz")
        )
        assertEquals(
            InboundMessageAction.ContactCallQuery("annem"),
            classifyCloud("!ara annem")
        )
        assertEquals(
            InboundMessageAction.OutboundSmsCommand("05321234567", "Selam"),
            classifyCloud("! sms 05321234567 Selam")
        )
        assertEquals(
            InboundMessageAction.ContactSmsQuery("Ahmet", "Yoldayım"),
            classifyCloud("!sms Ahmet Yoldayım")
        )
        assertEquals(
            InboundMessageAction.ContactSmsQuery("Ahmet Yılmaz", "Yoldayım geliyorum"),
            classifyCloud("! sms \"Ahmet Yılmaz\" Yoldayım geliyorum")
        )
    }

    @Test
    fun decodeMessageBodyUnwrapsLinphoneCpimAndIgnoresControlMessages() {
        val cpim = "From: <sip:t-switch_receiver@sip.linphone.org>\r\n" +
            "To: <sip:t-switch_pusher@sip.linphone.org>\r\n" +
            "DateTime: 2026-11-24T20:40:00Z\r\n" +
            "\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "\r\n" +
            "!ara annem"
        assertEquals(
            CommandAuthorization.DecodedMessageBody.Text("!ara annem"),
            CommandAuthorization.decodeMessageBody("message/cpim", cpim)
        )
        assertEquals(
            CommandAuthorization.DecodedMessageBody.IgnoreControl,
            CommandAuthorization.decodeMessageBody("application/im-iscomposing+xml", "<isComposing/>")
        )
        assertEquals(
            CommandAuthorization.DecodedMessageBody.IgnoreControl,
            CommandAuthorization.decodeMessageBody("message/imdn+xml", "<imdn/>")
        )
        val cpimImdn = "From: <sip:a@b>\r\n\r\nContent-Type: message/imdn+xml\r\n\r\n<imdn/>"
        assertEquals(
            CommandAuthorization.DecodedMessageBody.IgnoreControl,
            CommandAuthorization.decodeMessageBody("message/cpim", cpimImdn)
        )
    }
}
