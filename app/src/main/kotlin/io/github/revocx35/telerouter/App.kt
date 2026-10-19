package io.github.revocx35.telerouter

import android.app.Application
import io.github.revocx35.telerouter.assist.Assistant
import io.github.revocx35.telerouter.data.Store

class App : Application() {
    lateinit var store: Store
        private set
    lateinit var assistant: Assistant
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        assistant = Assistant(this, store)
    }
}

val android.content.Context.app get() = applicationContext as App
