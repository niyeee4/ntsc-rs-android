/* SPDX-License-Identifier: GPL-2.0-or-later */
package com.ntscrs.android

import android.app.Application

class NtscApp : Application() {
    lateinit var engine: NtscEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = NtscEngine()
    }
}
