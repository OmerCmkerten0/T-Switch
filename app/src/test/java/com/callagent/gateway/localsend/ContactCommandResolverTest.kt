package com.callagent.gateway.localsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactCommandResolverTest {

    private val contacts = listOf(
        ContactEntry("Ahmet Yılmaz", "+905321112233"),
        ContactEntry("Ahmet Kaya", "+905334445566"),
        ContactEntry("Mehmet Demir", "+905357778899"),
        ContactEntry("Annem", "+905421234567"),
        ContactEntry("Çağrı Şahin", "+905559990011"),
        ContactEntry("Ömer Çamkerten", "+905301010203")
    )

    @Test
    fun exactSingleMatchExecutesImmediatelyWithTurkishNormalization() {
        val resolver = ContactCommandResolver()
        val res = resolver.resolveCall("cagri sahin", contacts, nowMs = 1_000L)
        assertTrue("Expected ExecuteCall, got $res", res is ContactResolution.ExecuteCall)
        val exec = res as ContactResolution.ExecuteCall
        assertEquals("Çağrı Şahin", exec.contact.name)
        assertEquals("+905559990011", exec.contact.number)
        assertFalse(resolver.hasPending(nowMs = 1_000L))
    }

    @Test
    fun unambiguousSingleSubstringMatchExecutesImmediately() {
        val resolver = ContactCommandResolver()
        val res = resolver.resolveCall("mehmet", contacts, nowMs = 1_000L)
        assertTrue("Expected ExecuteCall, got $res", res is ContactResolution.ExecuteCall)
        assertEquals("Mehmet Demir", (res as ContactResolution.ExecuteCall).contact.name)
    }

    @Test
    fun singleFuzzyTypoMatchPromptsForConfirmationAndAcceptsEvetOr1() {
        val resolver = ContactCommandResolver()
        val res = resolver.resolveCall("mehmtt", contacts, nowMs = 1_000L)
        assertTrue("Expected AskConfirmation, got $res", res is ContactResolution.AskConfirmation)
        val ask = res as ContactResolution.AskConfirmation
        assertEquals(1, ask.candidates.size)
        assertEquals("Mehmet Demir", ask.candidates[0].name)
        assertTrue(ask.promptMessage.contains("Mehmet Demir"))
        assertTrue(resolver.hasPending(nowMs = 2_000L))

        val confirmed = resolver.handlePendingReply("evet", nowMs = 5_000L)
        assertTrue("Expected ExecuteCall on 'evet', got $confirmed", confirmed is ContactResolution.ExecuteCall)
        assertEquals("+905357778899", (confirmed as ContactResolution.ExecuteCall).contact.number)
        assertFalse(resolver.hasPending(nowMs = 5_000L))
    }

    @Test
    fun multipleMatchesPromptNumberedListAndSelectByIndex() {
        val resolver = ContactCommandResolver()
        val res = resolver.resolveCall("ahmet", contacts, nowMs = 1_000L)
        assertTrue("Expected AskConfirmation, got $res", res is ContactResolution.AskConfirmation)
        val ask = res as ContactResolution.AskConfirmation
        assertEquals(2, ask.candidates.size)
        assertTrue(ask.promptMessage.contains("1)"))
        assertTrue(ask.promptMessage.contains("2)"))

        val picked = resolver.handlePendingReply("2", nowMs = 3_000L)
        assertTrue("Expected ExecuteCall on '2', got $picked", picked is ContactResolution.ExecuteCall)
        assertEquals("Ahmet Kaya", (picked as ContactResolution.ExecuteCall).contact.name)
        assertEquals("+905334445566", picked.contact.number)
    }

    @Test
    fun smsContactResolutionSupportsImmediateAndInteractiveConfirmation() {
        val resolver = ContactCommandResolver()
        // Immediate single unambiguous match
        val immediate = resolver.resolveSms("annem", "Yoldayım geliyorum", contacts, nowMs = 1_000L)
        assertTrue("Expected ExecuteSms, got $immediate", immediate is ContactResolution.ExecuteSms)
        val sms = immediate as ContactResolution.ExecuteSms
        assertEquals("+905421234567", sms.contact.number)
        assertEquals("Yoldayım geliyorum", sms.text)

        // Multiple matches -> confirm with '1'
        val multi = resolver.resolveSms("ahmet", "Selam", contacts, nowMs = 2_000L)
        assertTrue(multi is ContactResolution.AskConfirmation)
        val confirmed = resolver.handlePendingReply("1", nowMs = 4_000L)
        assertTrue(confirmed is ContactResolution.ExecuteSms)
        assertEquals("Ahmet Yılmaz", (confirmed as ContactResolution.ExecuteSms).contact.name)
        assertEquals("Selam", confirmed.text)
    }

    @Test
    fun cancelAndExpiryClearPendingConfirmation() {
        val resolver = ContactCommandResolver()
        resolver.resolveCall("ahmet", contacts, nowMs = 1_000L)
        val cancelled = resolver.handlePendingReply("iptal", nowMs = 2_000L)
        assertTrue(cancelled is ContactResolution.Cancelled)
        assertFalse(resolver.hasPending(nowMs = 2_000L))

        resolver.resolveCall("ahmet", contacts, nowMs = 10_000L)
        assertFalse(resolver.hasPending(nowMs = 71_000L))
    }

    @Test
    fun noMatchReturnsNotFound() {
        val resolver = ContactCommandResolver()
        val res = resolver.resolveCall("zzzzzz", contacts, nowMs = 1_000L)
        assertTrue(res is ContactResolution.NotFound)
    }
}
