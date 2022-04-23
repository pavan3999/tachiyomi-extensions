package eu.kanade.tachiyomi.extension.pt.neoxscanlator

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.Exception

class NeoxScanlator :
    Madara(
        "Neox Scanlator",
        DEFAULT_BASE_URL,
        "pt-BR",
        SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
    ),
    ConfigurableSource {

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .addInterceptor(::titleCollectionIntercept)
        .addInterceptor(RateLimitInterceptor(1, 2, TimeUnit.SECONDS))
        .build()

    override val altNameSelector = ".post-content_item:contains(Alternativo) .summary-content"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String by lazy {
        preferences.getString(BASE_URL_PREF_KEY, DEFAULT_BASE_URL)!!
    }

    private var titleCollectionPath: String? = null

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", ACCEPT)
        .add("Accept-Language", ACCEPT_LANGUAGE)
        .add("Referer", REFERER)

    override fun popularMangaParse(response: Response): MangasPage {
        val popularPage = super.popularMangaParse(response)

        titleCollectionPath = popularPage.mangas.firstOrNull()?.url
            ?.removePrefix("/")
            ?.substringBefore("/")

        return popularPage
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val latestPage = super.latestUpdatesParse(response)

        titleCollectionPath = latestPage.mangas.firstOrNull()?.url
            ?.removePrefix("/")
            ?.substringBefore("/")

        return latestPage
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val searchPage = super.searchMangaParse(response)

        titleCollectionPath = searchPage.mangas.firstOrNull()?.url
            ?.removePrefix("/")
            ?.substringBefore("/")

        return searchPage
    }

    // Sometimes the site changes the manga URL. This override will
    // add an error instead of the HTTP 404 to inform the user to
    // migrate from Neox to Neox to update the URL.
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga))
            .asObservable()
            .doOnNext { response ->
                if (!response.isSuccessful) {
                    response.close()
                    throw Exception(if (response.code == 404) MIGRATION_MESSAGE else "HTTP error ${response.code}")
                }
            }
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val fixedUrl = (baseUrl + manga.url).toHttpUrl().newBuilder()
            .setPathSegment(0, titleCollectionPath ?: TITLE_PATH_PLACEHOLDER)
            .toString()

        return GET(fixedUrl, headers)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return client.newCall(chapterListRequest(manga))
            .asObservable()
            .doOnNext { response ->
                if (!response.isSuccessful) {
                    response.close()
                    throw Exception(if (response.code == 404) MIGRATION_MESSAGE else "HTTP error ${response.code}")
                }
            }
            .map(::chapterListParse)
    }

    override fun xhrChaptersRequest(mangaUrl: String): Request {
        val xhrHeaders = headersBuilder()
            .add("Referer", baseUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val fixedUrl = mangaUrl.toHttpUrl().newBuilder()
            .setPathSegment(0, titleCollectionPath ?: TITLE_PATH_PLACEHOLDER)
            .addPathSegments("ajax/chapters")
            .toString()

        return POST(fixedUrl, xhrHeaders)
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterUrl = (baseUrl + chapter.url.removePrefix(baseUrl)).toHttpUrlOrNull()
            ?: return super.pageListRequest(chapter)

        val fixedUrl = chapterUrl.newBuilder()
            .setPathSegment(0, titleCollectionPath ?: TITLE_PATH_PLACEHOLDER)
            .toString()

        return GET(fixedUrl, headers)
    }

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", ACCEPT_IMAGE)
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF_KEY
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(DEFAULT_BASE_URL)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Padrão: $DEFAULT_BASE_URL"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit()
                        .putString(BASE_URL_PREF_KEY, newValue as String)
                        .commit()
                    Toast.makeText(screen.context, RESTART_TACHIYOMI, Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        screen.addPreference(baseUrlPref)
    }

    private fun titleCollectionIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!request.url.toString().contains(TITLE_PATH_PLACEHOLDER)) {
            return chain.proceed(request)
        }

        val titlePathResult = runCatching {
            val popularResponse = chain.proceed(popularMangaRequest(1))
            val popularPage = popularMangaParse(popularResponse)

            popularPage.mangas.firstOrNull()?.url
                ?.removePrefix("/")
                ?.substringBefore("/")
        }

        titleCollectionPath = titlePathResult.getOrNull()

        val fixedUrl = request.url.toString()
            .replace(TITLE_PATH_PLACEHOLDER, titleCollectionPath ?: "comicz")

        val fixedRequest = request.newBuilder()
            .url(fixedUrl)
            .build()

        return chain.proceed(fixedRequest)
    }

    companion object {
        private const val MIGRATION_MESSAGE = "A URL deste mangá mudou. " +
            "Faça a migração da Neox para a Neox para atualizar a URL."

        private const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9"
        private const val ACCEPT_IMAGE = "image/webp,image/apng,image/*,*/*;q=0.8"
        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7,es;q=0.6,gl;q=0.5"
        private const val REFERER = "https://google.com/"

        private const val DEFAULT_BASE_URL = "https://neoxscans.net"
        private val BASE_URL_PREF_KEY = "base_url_${AppInfo.getVersionName()}"
        private const val BASE_URL_PREF_TITLE = "URL da fonte"
        private const val BASE_URL_PREF_SUMMARY = "Para uso temporário. Quando você atualizar a " +
            "extensão, esta configuração será apagada."

        private const val RESTART_TACHIYOMI = "Reinicie o Tachiyomi para aplicar as configurações."

        private val TITLE_PATH_PLACEHOLDER = UUID.randomUUID().toString()
    }
}
