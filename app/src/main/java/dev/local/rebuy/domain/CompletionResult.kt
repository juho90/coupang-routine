package dev.local.rebuy.domain

/** A purchase confirmation is tied to the cycle that the user actually saw. */
sealed interface CompletionResult {
    data object Completed : CompletionResult
    data object AlreadyRecorded : CompletionResult
    data object Stale : CompletionResult
    data object NotFound : CompletionResult
}
