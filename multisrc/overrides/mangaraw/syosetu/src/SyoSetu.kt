package eu.kanade.tachiyomi.extension.ja.syosetu

import eu.kanade.tachiyomi.multisrc.mangaraw.MangaRaw
import okhttp3.Request

class SyoSetu : MangaRaw("SyoSetu", "https://syosetu.top") {
    // syosetu.top doesn't have a popular manga page redirect to latest manga request
    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)
}
