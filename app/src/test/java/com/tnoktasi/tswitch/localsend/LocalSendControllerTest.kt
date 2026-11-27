package com.tnoktasi.tswitch.localsend

import com.tnoktasi.tswitch.sip.SipClient
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSendControllerTest {

    private fun localClient(peerFresh: Boolean = false): SipClient {
        val c = SipClient(
            username = "tswitch",
            password = "",
            serverDomain = "192.168.43.1",
            serverPort = 5060,
            localIp = "192.168.43.1",
            localPort = 5060,
            useTls = false,
            localSendMode = true
        )
        if (peerFresh) {
            c.setRegisteredPeerForTest("192.168.43.2" to 5060)
        }
        return c
    }

    private fun cloudClient(domain: String = "sip.linphone.org"): SipClient = SipClient(
        username = "gateway_user",
        password = "secret",
        serverDomain = domain,
        serverPort = 5061,
        localIp = "10.0.0.5",
        localPort = 5063,
        useTls = true,
        localSendMode = false
    )

    private fun customServerClient(domain: String = "pbx.example.com"): SipClient = SipClient(
        username = "gw-user",
        password = "secret",
        serverDomain = domain,
        serverPort = 5061,
        localIp = "10.0.0.5",
        localPort = 5062,
        useTls = true,
        localSendMode = false
    )

    @Test
    fun preferCloudRoutesViaCloudSipEvenWhenLocalPeerIsFresh() {
        val local = localClient(peerFresh = true)
        val cloud = cloudClient()

        val route = LocalSendController.selectFakeSmsRoute(
            preferCloud = true,
            primarySip = local,
            cloudSip = cloud
        )

        assertEquals(LocalSendController.FakeSmsRoute.CLOUD, route)
    }

    @Test
    fun preferCloudFalseRoutesViaCloudWhenPrimaryMissingOrLocalPeerStale() {
        val cloud = cloudClient()

        assertEquals(
            LocalSendController.FakeSmsRoute.CLOUD,
            LocalSendController.selectFakeSmsRoute(
                preferCloud = false,
                primarySip = null,
                cloudSip = cloud
            )
        )
        assertEquals(
            LocalSendController.FakeSmsRoute.CLOUD,
            LocalSendController.selectFakeSmsRoute(
                preferCloud = false,
                primarySip = localClient(peerFresh = false),
                cloudSip = cloud
            )
        )
        assertEquals(
            LocalSendController.FakeSmsRoute.LOCAL_PEER,
            LocalSendController.selectFakeSmsRoute(
                preferCloud = false,
                primarySip = localClient(peerFresh = true),
                cloudSip = cloud
            )
        )
    }

    @Test
    fun blankCloudServerDomainIsGuardedAgainstCloudRouting() {
        val local = localClient(peerFresh = false)
        val blankCloud = cloudClient(domain = "   ")

        assertEquals(
            LocalSendController.FakeSmsRoute.NONE,
            LocalSendController.selectFakeSmsRoute(
                preferCloud = true,
                primarySip = local,
                cloudSip = blankCloud
            )
        )
        assertEquals(
            LocalSendController.FakeSmsRoute.LOCAL_PEER,
            LocalSendController.selectFakeSmsRoute(
                preferCloud = false,
                primarySip = local,
                cloudSip = blankCloud
            )
        )
    }

    @Test
    fun customSipModeWithoutCloudCompanionRoutesViaPrimaryServer() {
        val custom = customServerClient()

        assertEquals(
            LocalSendController.FakeSmsRoute.PRIMARY_SERVER,
            LocalSendController.selectFakeSmsRoute(
                preferCloud = true,
                primarySip = custom,
                cloudSip = null
            )
        )
    }
}
