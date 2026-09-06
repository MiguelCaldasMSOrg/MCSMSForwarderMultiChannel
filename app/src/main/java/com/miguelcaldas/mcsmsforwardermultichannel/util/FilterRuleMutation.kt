package com.miguelcaldas.mcsmsforwardermultichannel.util

/** Serializes every read/merge/write transaction that can mutate sender or message rules. */
internal object FilterRuleMutationCoordinator {
    private val lock = Any()

    fun <T> withLock(block: () -> T): T =
        synchronized(lock) {
            block()
        }
}

/**
 * Applies the user's draft to its baseline while retaining entries added by another writer after
 * the draft was opened. Remote commands and provisioning only add entries, so baseline entries
 * absent from the draft remain intentional user deletions.
 */
internal fun <T> mergeDraftWithConcurrentAdditions(
    baseline: List<T>,
    draft: List<T>,
    current: List<T>,
    equivalent: (T, T) -> Boolean,
): List<T> {
    val merged = draft.toMutableList()
    current.forEach { candidate ->
        val existedInBaseline = baseline.any { equivalent(it, candidate) }
        if (!existedInBaseline && merged.none { equivalent(it, candidate) }) {
            merged.add(candidate)
        }
    }
    return merged
}
