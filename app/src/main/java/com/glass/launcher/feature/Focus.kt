package com.glass.launcher.feature

import com.glass.launcher.App

/** Focus mode: while on, only chosen apps may drop a banner. */
object Focus {
    fun allows(pkg: String): Boolean {
        val p = App.instance.prefs
        return !p.focusOn || pkg in p.focusAllowed
    }
}
