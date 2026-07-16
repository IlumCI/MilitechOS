package eu.euroswarms.surgeon

import android.app.Application
import eu.euroswarms.surgeon.work.Notifier

class SurgeonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }
}
