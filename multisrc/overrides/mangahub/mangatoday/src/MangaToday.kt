package eu.kanade.tachiyomi.extension.en.mangatoday

import eu.kanade.tachiyomi.lib.ratelimit.SpecificHostRateLimitInterceptor
import eu.kanade.tachiyomi.multisrc.mangahub.MangaHub
import okhttp3.OkHttpClient

class MangaToday : MangaHub(
    "MangaToday",
    "https://mangatoday.fun",
    "en"
) {
    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor(SpecificHostRateLimitInterceptor(cdnHost, 1, 2))
        .build()

    override val serverId = "m03"
}
