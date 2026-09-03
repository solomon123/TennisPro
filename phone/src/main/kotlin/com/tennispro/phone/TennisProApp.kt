package com.tennispro.phone

import android.app.Application
import com.tennispro.phone.storage.MatchStorage
import com.tennispro.phone.wear.WearLink

/**
 * Manual dependency wiring.
 *
 * Two long-lived collaborators and no graph worth generating, so a DI framework
 * would be pure dependency weight. Revisit if the object count grows past a
 * handful.
 */
class TennisProApp : Application() {

    val storage: MatchStorage by lazy { MatchStorage(this) }
    val wearLink: WearLink by lazy { WearLink(this) }
}
