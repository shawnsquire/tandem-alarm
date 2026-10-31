package me.shawnsquire.tandem.sync

import com.google.firebase.FirebaseException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.FirebaseFunctionsException.Code
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.IOException

/** A pairing failure with a message that is safe to show to the user. */
class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val GENERIC = "Something went wrong. Try again."
private const val OFFLINE = "No connection. Try again when you're online."
private const val NOT_SIGNED_IN = "Not signed in yet. Check your connection and try again."

internal fun pairingErrorMessage(code: Code, serverMessage: String?): String = when (code) {
    Code.NOT_FOUND -> "That code is invalid or has expired."
    Code.FAILED_PRECONDITION -> when {
        serverMessage?.contains("household is full", ignoreCase = true) == true -> "That household already has two phones."
        serverMessage?.contains("already in a household", ignoreCase = true) == true -> "This phone is already paired. Unpair first."
        else -> "Can't pair right now."
    }
    Code.INVALID_ARGUMENT -> "Enter the pairing code."
    Code.UNAUTHENTICATED -> NOT_SIGNED_IN
    Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED -> OFFLINE
    else -> GENERIC
}

internal fun pairingStatus(household: Flow<Household?>, partner: Flow<PartnerState?>): Flow<PairingStatus> =
    combine(household, partner) { h, p ->
        if (h == null) {
            PairingStatus.Unpaired
        } else {
            PairingStatus.Paired(h.id, p?.displayName?.takeIf { it.isNotBlank() })
        }
    }.distinctUntilChanged()

/** Pairing via the `createPairCode` / `joinHousehold` / `unpair` callables. Failures surface as [PairingException]. */
class PairingRepositoryImpl(
    private val auth: AuthManager,
    household: Flow<Household?>,
    partner: Flow<PartnerState?>,
    private val functions: FirebaseFunctions,
) : PairingRepository {

    override val status: Flow<PairingStatus> = pairingStatus(household, partner)

    override suspend fun createCode(): String {
        val data = call("createPairCode", null)
        return (data as? Map<*, *>)?.get("code") as? String
            ?: throw PairingException(GENERIC)
    }

    override suspend fun join(code: String) {
        call("joinHousehold", mapOf("code" to code.trim()))
    }

    override suspend fun unpair() {
        call("unpair", null)
    }

    private suspend fun call(name: String, args: Any?): Any? {
        try {
            auth.ensureSignedIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseException) {
            throw PairingException(NOT_SIGNED_IN, e)
        } catch (e: IOException) {
            throw PairingException(OFFLINE, e)
        } catch (e: Exception) {
            throw PairingException(NOT_SIGNED_IN, e)
        }
        try {
            return functions.getHttpsCallable(name).call(args).await().getData()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFunctionsException) {
            throw PairingException(pairingErrorMessage(e.code, e.message), e)
        }
    }
}
