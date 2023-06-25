package eu.kanade.tachiyomi.extension.en.mangalife

import eu.kanade.tachiyomi.multisrc.nepnep.NepNep
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MangaLife : NepNep("MangaLife", "https://manga4life.com", "en") {

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .build()
}
