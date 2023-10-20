package eu.kanade.tachiyomi.extension.pt.goldenmangas

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.randomua.PREF_KEY_CUSTOM_UA
import eu.kanade.tachiyomi.lib.randomua.PREF_KEY_RANDOM_UA
import eu.kanade.tachiyomi.lib.randomua.RANDOM_UA_ENTRIES
import eu.kanade.tachiyomi.lib.randomua.getPrefCustomUA
import eu.kanade.tachiyomi.lib.randomua.getPrefUAType
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class GoldenMangas : ParsedHttpSource(), ConfigurableSource {

    // Hardcode the id because the language wasn't specific.
    override val id: Long = 6858719406079923084

    override val name = "Golden Mangás"

    override val baseUrl = "https://www.goldenmanga.top"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val baseHttpUrl: HttpUrl
        get() = preferences.baseUrl.toHttpUrl()

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .addInterceptor(ObsoleteExtensionInterceptor())
        .setRandomUserAgent(
            userAgentType = preferences.getPrefUAType(),
            customUA = preferences.getPrefCustomUA(),
        )
        .rateLimitPath("/mangas", 1, 8.seconds)
        .rateLimitPath("/mm-admin/uploads", 1, 8.seconds)
        .rateLimitPath("/timthumb.php", 1, 3.seconds)
        .rateLimitPath("/index.php", 1, 3.seconds)
        .addInterceptor(::guessNewUrlIntercept)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Accept", ACCEPT)
        .add("Accept-Language", ACCEPT_LANGUAGE)
        .add("Referer", REFERER)

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaSelector(): String = "div#maisLidos div.itemmanga:not(:contains(Avisos e Recados))"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("h3")!!.text().withoutLanguage()
        thumbnail_url = element.selectFirst("img")!!.absUrl("src")
        url = element.attr("href")
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request {
        val path = if (page > 1) "/index.php?pagina=$page" else ""
        return GET("$baseUrl$path", headers)
    }

    override fun latestUpdatesSelector() = "div.col-sm-12.atualizacao > div.row:not(:contains(Avisos e Recados))"

    override fun latestUpdatesFromElement(element: Element): SManga = SManga.create().apply {
        val infoElement = element.selectFirst("div.col-sm-10.col-xs-8 h3")!!
        val thumbElement = element.selectFirst("a:first-child div img")!!

        title = infoElement.text().withoutLanguage()
        thumbnail_url = thumbElement.absUrl("src")
            .replace("w=100&h=140", "w=380&h=600")
        url = element.select("a:first-child").attr("href")
    }

    override fun latestUpdatesNextPageSelector() = "ul.pagination li:last-child a"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val newHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/mangas")
            .build()

        val url = "$baseUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("busca", query)
            .toString()

        return GET(url, newHeaders)
    }

    override fun searchMangaSelector() = "div.mangas.col-lg-2 a"

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("h3")!!.text().withoutLanguage()
        thumbnail_url = element.selectFirst("img")!!.absUrl("src")
        url = element.attr("href")
    }

    override fun searchMangaNextPageSelector(): String? = null

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaResult = runCatching { super.mangaDetailsParse(response) }
        val manga = mangaResult.getOrNull()

        if (manga?.title.isNullOrEmpty() && !response.hasChangedDomain) {
            throw Exception(MIGRATE_WARNING)
        }

        return manga!!
    }

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val infoElement = document.selectFirst("div.row > div.col-sm-8 > div.row")!!
        val firstColumn = infoElement.selectFirst("div.col-sm-4.text-right > img")!!
        val secondColumn = infoElement.selectFirst("div.col-sm-8")!!

        title = secondColumn.select("h2:eq(0)").text().withoutLanguage()
        author = secondColumn.select("h5:contains(Autor)").text().withoutLabel()
        artist = secondColumn.select("h5:contains(Artista)").text().withoutLabel()
        genre = secondColumn.select("h5:contains(Genero) a").toList()
            .filter { it.text().isNotEmpty() }
            .joinToString { it.text() }
        status = secondColumn.select("h5:contains(Status) a").text().toStatus()
        description = document.select("#manga_capitulo_descricao").text()
        thumbnail_url = firstColumn.attr("abs:src")
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chaptersResult = runCatching { super.chapterListParse(response) }
        val chapterList = chaptersResult.getOrNull()

        if (chapterList.isNullOrEmpty() && !response.hasChangedDomain) {
            throw Exception(MIGRATE_WARNING)
        }

        return chapterList.orEmpty()
    }

    override fun chapterListSelector() = "ul#capitulos li.row"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val firstColumn = element.selectFirst("a > div.col-sm-5")!!
        val secondColumn = element.select("div.col-sm-5.text-right a:not([href^='/'])")

        name = firstColumn.text().substringBefore("(").trim()
        scanlator = secondColumn.joinToString { it.text() }
        date_upload = firstColumn.select("div.col-sm-5 span[style]").text().toDate()
        url = element.select("a").attr("href")
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val newHeaders = headersBuilder()
            .set("Referer", baseUrl + chapter.url.substringBeforeLast("/"))
            .build()

        return GET(baseUrl + chapter.url, newHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val pagesResult = runCatching { super.pageListParse(response) }
        val pageList = pagesResult.getOrNull()

        if (pageList.isNullOrEmpty() && !response.hasChangedDomain) {
            throw Exception(MIGRATE_WARNING)
        }

        return pageList.orEmpty()
    }

    override fun pageListParse(document: Document): List<Page> {
        val chapterImages = document.selectFirst("div.col-sm-12[id^='capitulos_images']:has(img[pag])")

        val isNovel = document.selectFirst(".block_text_border") !== null

        if (chapterImages == null && isNovel) {
            throw Exception(CHAPTER_IS_NOVEL_ERROR)
        }

        return chapterImages?.select("img[pag]")
            .orEmpty()
            .mapIndexed { i, element ->
                Page(i, document.location(), element.attr("abs:src"))
            }
    }

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", ACCEPT_IMAGE)
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val uaPreference = ListPreference(screen.context).apply {
            key = PREF_KEY_RANDOM_UA
            title = "User Agent aleatório"
            summary = "%s"
            entries = arrayOf("Desativado", "Desktop", "Celular")
            entryValues = RANDOM_UA_ENTRIES
            setDefaultValue("off")

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP_MESSAGE, Toast.LENGTH_SHORT).show()
                true
            }
        }

        val customUaPreference = EditTextPreference(screen.context).apply {
            key = PREF_KEY_CUSTOM_UA
            title = "User Agent personalizado"
            summary = "Deixe em branco para usar o User Agent padrão do aplicativo. " +
                "Ignorado se User Agent aleatório está ativado."
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    Headers.Builder().add("User-Agent", newValue as String).build()
                    Toast.makeText(screen.context, RESTART_APP_MESSAGE, Toast.LENGTH_SHORT).show()
                    true
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(screen.context, "User Agent inválido: ${e.message}", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }

        screen.addPreference(uaPreference)
        screen.addPreference(customUaPreference)
    }

    private fun guessNewUrlIntercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (chain.request().url.host == "raw.githubusercontent.com") {
            return response
        }

        if (response.hasChangedDomain && preferences.baseUrl == baseUrl) {
            return response
        }

        preferences.baseUrl = "https://${response.request.url.host}"

        val newUrl = chain.request().url.toString()
            .replaceFirst(baseUrl, preferences.baseUrl)
            .toHttpUrl()
        val newRequest = chain.request().newBuilder()
            .url(newUrl)
            .build()

        response.close()

        return chain.proceed(newRequest)
    }

    private var SharedPreferences.baseUrl: String
        get() = getString(BASE_URL_PREF, this@GoldenMangas.baseUrl)!!
        set(newValue) = edit().putString(BASE_URL_PREF, newValue).apply()

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    private val Response.hasChangedDomain: Boolean
        get() = request.url.host != baseHttpUrl.host &&
            request.url.host.contains("goldenmang")

    private fun String.toStatus() = when (this) {
        "Ativo" -> SManga.ONGOING
        "Completo" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun String.withoutLabel(): String = substringAfter(":").trim()

    private fun String.withoutLanguage(): String = replace(FLAG_REGEX, "").trim()

    companion object {
        private const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9"
        private const val ACCEPT_IMAGE = "image/webp,image/apng,image/*,*/*;q=0.8"
        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7,es;q=0.6,gl;q=0.5"
        private const val REFERER = "https://google.com/"

        private val FLAG_REGEX = "\\((Pt[-/]br|Scan)\\)".toRegex(RegexOption.IGNORE_CASE)

        private const val CHAPTER_IS_NOVEL_ERROR =
            "O capítulo é uma novel em formato de texto e não possui imagens."

        private const val MIGRATE_WARNING = "Migre o item da Golden Mangás para Golden Mangás para atualizar a URL."

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("(dd/MM/yyyy)", Locale("pt", "BR"))
        }

        private const val RESTART_APP_MESSAGE = "Reinicie o aplicativo para aplicar as alterações."
        private const val BASE_URL_PREF = "base_url"
    }
}
