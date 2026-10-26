package mebot.nguli.com.feed.auth

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Local-only removal: no provider revocation, deletion or live network. */
class ChatGptSavedAccountRemovalTest {
    private val fixture=AuthFixture()
    @After fun close() { fixture.manager.cancelSignIn();fixture.scope.cancel() }

    @Test fun removingInactiveThenActiveAccountPreservesOtherRowsAndHost() = runBlocking {
        val active=fixture.seed()
        val other=active.copy(id="other-registration",clientId="oaiapp_other")
        fixture.store.saved=fixture.store.saved!!.copy(accounts=listOf(active,other))
        val host=fixture.store.saved!!.hostId
        assertTrue(fixture.manager.removeSavedAccount(other.id))
        assertEquals(listOf(active),fixture.store.saved!!.accounts)
        assertEquals(active.id,fixture.manager.state.value.activeAccountId)
        assertTrue(fixture.manager.removeSavedAccount(active.id))
        assertTrue(fixture.store.saved!!.accounts.isEmpty())
        assertNull(fixture.manager.state.value.activeAccountId)
        assertEquals(host,fixture.store.saved!!.hostId)
        assertEquals(0,fixture.revokeCalls.get())
        assertEquals(0,fixture.tokenCalls.get())
        assertEquals(0,fixture.modelCalls.get())
    }

    @Test fun signedOutRowRemovalIsDurableAndIdempotent() = runBlocking {
        val old=fixture.seed().withoutCredentials()
        fixture.store.saved=fixture.store.saved!!.copy(accounts=listOf(old),activeAccountId=null)
        assertTrue(fixture.manager.removeSavedAccount(old.id))
        assertFalse(fixture.manager.removeSavedAccount(old.id))
        val restarted=ChatGptAuthManager(fixture.store,fixture.transport,fixture.now::get,
            ChatGptCallbackCopy("Return","Fixture","Invalid","Open","mebot.nguli.com.debug"),fixture.scope)
        restarted.initialize()
        assertTrue(restarted.state.value.accounts.isEmpty())
        assertEquals(0,fixture.revokeCalls.get())
    }

    @Test fun tokenRotationAlreadyInFlightCannotResurrectRemovedAccount() = runBlocking {
        val old=fixture.seed()
        fixture.tokenStarted=CompletableDeferred();fixture.tokenContinue=CompletableDeferred()
        val request=async(Dispatchers.Default) { runCatching { fixture.manager.accessToken(old.id) } }
        withTimeout(5000) { fixture.tokenStarted!!.await() }
        try { withTimeout(5000) { fixture.manager.removeSavedAccount(old.id) } }
        finally { fixture.tokenContinue!!.complete(Unit) }
        assertTrue(request.await().isFailure)
        assertTrue(fixture.store.saved!!.accounts.isEmpty())
        assertTrue(fixture.manager.state.value.accounts.isEmpty())
        assertEquals(0,fixture.revokeCalls.get())
    }

    @Test fun removalDoesNotStrandAnUnrelatedAccountsRotatedRefreshToken() = runBlocking {
        val rotating=fixture.seed()
        val removed=rotating.copy(id="other-account",clientId="oaiapp_other")
        fixture.store.saved=fixture.store.saved!!.copy(accounts=listOf(rotating,removed))
        fixture.tokenStarted=CompletableDeferred();fixture.tokenContinue=CompletableDeferred()
        val request=async(Dispatchers.Default) { fixture.manager.accessToken(rotating.id) }
        withTimeout(5000) { fixture.tokenStarted!!.await() }
        try { fixture.manager.removeSavedAccount(removed.id) }
        finally { fixture.tokenContinue!!.complete(Unit) }
        assertEquals("fixture-access-next",request.await())
        assertEquals(rotating.id,fixture.store.saved!!.accounts.single().id)
        assertEquals("fixture-refresh-next",fixture.store.saved!!.accounts.single().refreshToken)
    }

    @Test fun failedDurableRemovalDoesNotClaimRowWasRemoved() = runBlocking {
        val old=fixture.seed()
        val failing=object:ChatGptCredentialStore {
            override fun read()=fixture.store.read()
            override fun write(state:ChatGptPrivateState) { throw IllegalStateException("fixture write failed") }
            override fun clear() { error("Not a reset") }
        }
        val manager=ChatGptAuthManager(failing,fixture.transport,fixture.now::get,
            ChatGptCallbackCopy("Return","Fixture","Invalid","Open","mebot.nguli.com.debug"),fixture.scope)
        manager.initialize()
        assertTrue(runCatching { manager.removeSavedAccount(old.id) }.isFailure)
        assertEquals(listOf(old),fixture.store.saved!!.accounts)
        assertEquals(old.id,manager.state.value.accounts.single().id)
        assertEquals(old.id,manager.state.value.activeAccountId)
        assertEquals(0,fixture.revokeCalls.get())
    }

    @Test fun pendingReturningSignInIsCancelledBeforeLocalRemoval() = runBlocking {
        val old=fixture.seed()
        fixture.manager.beginSignIn(old.id)
        assertEquals("waiting",fixture.manager.state.value.phase)
        fixture.manager.removeSavedAccount(old.id)
        assertEquals("idle",fixture.manager.state.value.phase)
        assertTrue(fixture.store.saved!!.accounts.isEmpty())
        assertTrue(runCatching { fixture.manager.awaitSignIn() }.isFailure)
        assertEquals(0,fixture.tokenCalls.get())
    }
}
