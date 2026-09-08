package com.taxiinspector.data.rides

/**
 * The outcome of saving a company. Rejections are returned rather than thrown: a duplicate
 * name or a full list is ordinary user input that the editor must explain, not a defect.
 */
enum class CompanySaveResult {
    Saved,

    /** Another company already holds this trimmed, case-folded name. */
    DuplicateName,

    /** Ten companies are already saved; the app never evicts one to make room. */
    LimitReached,

    /** Companies are immutable while any ride is active, including Paused and interrupted. */
    RideActive,

    /** The company being edited was deleted in the meantime. */
    CompanyMissing,
}

/** Selecting and deleting can fail in exactly the same two ways, so they share one result. */
enum class CompanyChangeResult { Done, RideActive, CompanyMissing }
