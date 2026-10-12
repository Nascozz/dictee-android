package com.nascozz.dictee

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Client HTTP unique de l'app. Les variantes (délais différents, WebSocket…) en dérivent avec
 * newBuilder() et partagent ainsi les connexions et les threads au lieu d'en créer chacune.
 */
object Http {
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
