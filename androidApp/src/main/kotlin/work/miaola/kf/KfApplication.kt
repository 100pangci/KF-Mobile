package work.miaola.kf

import android.app.Application

class KfApplication : Application() {
    lateinit var store: SecureStore; private set
    lateinit var services: HttpServiceFactory; private set
    override fun onCreate() {
        super.onCreate()
        store = SecureStore(this)
        services = HttpServiceFactory(store)
    }
}
