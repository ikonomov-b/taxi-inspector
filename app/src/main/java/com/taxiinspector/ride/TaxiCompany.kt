package com.taxiinspector.ride

/**
 * A saved local label around exactly one [Tariff]. The name is a user-supplied convenience
 * label, never a verified business identity, and it never enters fare arithmetic.
 */
data class TaxiCompany(
    val id: String,
    val name: String,
    val tariff: Tariff,
) {
    init {
        require(id.isNotBlank()) { "Company id cannot be blank." }
        require(name.isNotBlank()) { "Company name cannot be blank." }
        require(name == name.trim()) { "Company name must be stored trimmed." }
        require(name.length <= MAX_NAME_LENGTH) {
            "Company name cannot exceed $MAX_NAME_LENGTH characters."
        }
    }

    /** The stored duplicate-rejection key for this name. */
    val nameKey: String get() = nameKey(name)

    companion object {
        const val MAX_COMPANIES = 10
        const val MAX_NAME_LENGTH = 80

        /**
         * Trimmed and case-folded so the picker cannot hold two indistinguishable entries.
         * `lowercase()` without a locale is deliberate: the stored key must not depend on the
         * device locale, and it folds non-ASCII names that SQLite's `NOCASE` would not.
         */
        fun nameKey(name: String): String = name.trim().lowercase()
    }
}
