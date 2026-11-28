// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import guru.parso.saldima.core.Clock
import java.time.LocalDate

/** The real `Clock`: wall-clock time, used only where `:core` needs "today" injected. */
class SystemClock : Clock {
    override fun todayIso(): String = LocalDate.now().toString()
}
