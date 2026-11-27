// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/** One leg of a scheduled transaction's template. Signed: positive debits, negative credits. */
data class XmlScheduledSplit(
    val accountGuid: Guid,
    val value: Money,
)

/** Mutually exclusive end conditions, matching desktop GnuCash; `null` (no instance) means it never ends. */
sealed class ScheduledEnd {
    data class Occurrences(val count: Int) : ScheduledEnd()
    data class OnDate(val date: String) : ScheduledEnd()
}

enum class DueInstanceAction { AUTO_POST, AUTO_POST_NOTIFY, NEEDS_APPROVAL, REMINDER }

data class DueScheduledInstance(
    val scheduledTransactionGuid: Guid,
    val name: String,
    val date: String,
    val action: DueInstanceAction,
)

/**
 * A GnuCash `GncSchedXaction` (desktop's "Scheduled Transactions"): a
 * recurrence rule plus a template of splits that `postScheduledInstance`
 * turns into a real transaction when due. Milestone 1 (tracked in the
 * repo's issue): fixed amounts only, no algebraic formulas; no deferred
 * ("postpone") instances.
 */
data class XmlScheduledTransaction(
    val guid: Guid,
    val name: String,
    val description: String,
    val currencyCode: String,
    val enabled: Boolean = true,
    /** Silently post a due instance with no review. */
    val autoCreate: Boolean = false,
    /** Only meaningful with `autoCreate`: still post silently, but flag it for review. */
    val autoCreateNotify: Boolean = false,
    /** How many days before the due date an instance is created/posted. */
    val advanceCreateDays: Int = 0,
    /** How many further days before that an upcoming instance is shown as a reminder. */
    val advanceRemindDays: Int = 0,
    val start: String,
    /** `YYYY-MM-DD`; `null` if no instance has posted yet. */
    val lastOccurred: String? = null,
    val instanceCount: Int = 0,
    val end: ScheduledEnd? = null,
    val recurrence: Recurrence,
    val templateAccountGuid: Guid,
    val templateSplits: List<XmlScheduledSplit>,
)
