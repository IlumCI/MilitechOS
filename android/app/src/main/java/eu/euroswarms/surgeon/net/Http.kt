package eu.euroswarms.surgeon.net

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, val body: String, message: String) : Exception(message)

object Http {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // model calls can be slow
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}
