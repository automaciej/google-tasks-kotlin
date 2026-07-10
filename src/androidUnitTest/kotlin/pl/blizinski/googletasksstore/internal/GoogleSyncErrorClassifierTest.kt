package pl.blizinski.googletasksstore.internal

import com.google.api.client.googleapis.testing.json.GoogleJsonResponseExceptionFactoryTesting
import com.google.api.client.json.gson.GsonFactory
import pl.blizinski.tasksync.SyncErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [GoogleJsonResponseExceptionFactoryTesting] builds a real, throwable
 *  [com.google.api.client.googleapis.json.GoogleJsonResponseException] in-process, with no
 *  HTTP/Android mocking needed -- it ships specifically for this purpose. */
private fun jsonResponseException(statusCode: Int, reasonPhrase: String) =
    GoogleJsonResponseExceptionFactoryTesting.newMock(GsonFactory.getDefaultInstance(), statusCode, reasonPhrase)

/** Class name (not type) is what `isAuthError` checks for -- see that function's doc comment on
 *  why it walks the cause chain by name rather than an `is` check. */
private class FakeGoogleAuthException(message: String) : Exception(message)

class GoogleSyncErrorClassifierTest {

    private val classifier = GoogleSyncErrorClassifier()

    @Test
    fun classifySpecialReturnsAdvancedProtectionFor403WithMatchingMessage() {
        val e = jsonResponseException(403, "access_denied")
        assertEquals(SyncErrorKind.ADVANCED_PROTECTION, classifier.classifySpecial(e))
    }

    @Test
    fun classifySpecialReturnsNullFor403WithUnrelatedMessage() {
        val e = jsonResponseException(403, "Forbidden")
        assertNull(classifier.classifySpecial(e))
    }

    @Test
    fun classifySpecialReturnsNullForNon403Status() {
        val e = jsonResponseException(500, "access_denied")
        assertNull(classifier.classifySpecial(e))
    }

    @Test
    fun classifySpecialReturnsAuthFailedForGoogleAuthExceptionByName() {
        assertEquals(SyncErrorKind.AUTH_FAILED, classifier.classifySpecial(FakeGoogleAuthException("token expired")))
    }

    @Test
    fun classifySpecialReturnsAuthFailedWhenGoogleAuthExceptionIsACause() {
        val wrapped = Exception("wrapper", FakeGoogleAuthException("token expired"))
        assertEquals(SyncErrorKind.AUTH_FAILED, classifier.classifySpecial(wrapped))
    }

    @Test
    fun classifySpecialReturnsNullForUnrelatedException() {
        assertNull(classifier.classifySpecial(RuntimeException("network blip")))
    }

    @Test
    fun httpStatusReadsStatusCodeFromGoogleJsonResponseException() {
        assertEquals(404, classifier.httpStatus(jsonResponseException(404, "Not Found")))
    }

    @Test
    fun httpStatusIsNullForUnrelatedException() {
        assertNull(classifier.httpStatus(RuntimeException("network blip")))
    }

    @Test
    fun extractConsentIntentIsNullForUnrelatedException() {
        // The true (UserRecoverableAuthIOException) branch isn't covered here: constructing one
        // requires com.google.android.gms.auth.UserRecoverableAuthException, which is not on
        // this module's compile classpath (a transitive-only, not-declared dependency of
        // google-api-client-android) -- see this stage's design-doc note on the accepted gap.
        assertNull(classifier.extractConsentIntent(RuntimeException("network blip")))
    }
}
