package dev.local.rebuy.domain

sealed interface CompletionResult {
    data object Completed : CompletionResult
    data object AlreadyRecorded : CompletionResult
    data object Stale : CompletionResult
    data object NotFound : CompletionResult
}
