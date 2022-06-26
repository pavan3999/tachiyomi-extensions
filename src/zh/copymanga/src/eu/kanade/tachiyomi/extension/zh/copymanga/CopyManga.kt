package eu.kanade.tachiyomi.extension.zh.copymanga

import android.app.Application
import android.content.SharedPreferences
import com.luhuiguo.chinese.ChineseUtils
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CopyManga : ConfigurableSource, HttpSource() {

    override val name = "拷贝漫画"
    override val baseUrl = "https://www.copymanga.org"
    override val lang = "zh"
    override val supportsLatest = true
    private val popularLatestPageSize = 50 // default
    private val searchPageSize = 12 // default
    private val apiUrl = "https://api.copymanga.org"

    val replaceToMirror2 = Regex("mirror277\\.mangafuna\\.xyz\\:12001")
    val replaceToMirror = Regex("mirror77\\.mangafuna\\.xyz\\:12001")
    // val replaceToMirror2 = Regex("1767566263\\.rsc\\.cdn77\\.org")
    // val replaceToMirror = Regex("1025857477\\.rsc\\.cdn77\\.org")

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(1, 2) // 1 request per 2 seconds
        .build()

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/comics?ordering=-popular&offset=${(page - 1) * popularLatestPageSize}&limit=$popularLatestPageSize", headers)
    override fun popularMangaParse(response: Response): MangasPage = parseSearchMangaWithFilterOrPopularOrLatestResponse(response)
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/comics?ordering=-datetime_updated&offset=${(page - 1) * popularLatestPageSize}&limit=$popularLatestPageSize", headers)
    override fun latestUpdatesParse(response: Response): MangasPage = parseSearchMangaWithFilterOrPopularOrLatestResponse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // when perform html search, sort by popular
        val apiUrlString = "$baseUrl/api/kb/web/searchs/comics?limit=$searchPageSize&offset=${(page - 1) * searchPageSize}&platform=2&q=$query&q_type="
//        val apiUrlString = "$baseUrl/api/v3/search/comic?limit=$searchPageSize&offset=${(page - 1) * searchPageSize}&platform=2&q=$query&q_type="
        val htmlUrlString = "$baseUrl/comics?offset=${(page - 1) * popularLatestPageSize}&limit=$popularLatestPageSize"
        val requestUrlString: String

        val params = filters.map {
            if (it is MangaFilter) {
                it.toUriPart()
            } else ""
        }.filter { it != "" }.joinToString("&")
        // perform html search only when do have filter and not search anything
        if (params != "" && query == "") {
            requestUrlString = "$htmlUrlString&$params"
        } else {
            requestUrlString = apiUrlString
        }
        val url = requestUrlString.toHttpUrlOrNull()?.newBuilder()
        return GET(url.toString(), headers)
    }
    override fun searchMangaParse(response: Response): MangasPage {
        if (response.headers("content-type").filter { it.contains("json", true) }.any()) {
            // result from api request
            return parseSearchMangaResponseAsJson(response)
        } else {
            // result from html request
            return parseSearchMangaWithFilterOrPopularOrLatestResponse(response)
        }
    }

    override fun mangaDetailsRequest(manga: SManga) = GET(baseUrl + manga.url, headers)
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        var _title: String = document.select("div.comicParticulars-title-right > ul > li:eq(0) ").first().text()
        if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
            _title = ChineseUtils.toSimplified(_title)
        }
        val manga = SManga.create().apply {
            title = _title
            var picture = document.select("div.comicParticulars-title-left img").first().attr("data-src")
            if (preferences.getBoolean(CHANGE_CDN_OVERSEAS, false)) {
                picture = replaceToMirror2.replace(picture, "mirror2.mangafunc.fun:443")
                picture = replaceToMirror.replace(picture, "mirror.mangafunc.fun:443")
            }
            thumbnail_url = picture
            description = document.select("div.comicParticulars-synopsis p.intro").first().text().trim()
        }

        val items = document.select("div.comicParticulars-title-right ul li")
        if (items.size >= 7) {
            manga.author = items[2].select("a").map { i -> i.text().trim() }.joinToString(", ")
            manga.status = when (items[5].select("span.comicParticulars-right-txt").first().text().trim()) {
                "已完結" -> SManga.COMPLETED
                "連載中" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            manga.genre = items[6].select("a").map { i -> i.text().trim().trim('#') }.joinToString(", ")
        }
        return manga
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val disposablePass = document.selectFirst("script:containsData(dio)").data()
            .substringAfter("'").substringBeforeLast("'")

        // Get encrypted chapters data from another endpoint
        val chapterResponse =
            client.newCall(GET("${response.request.url}/chapters", headers)).execute()
        val disposableData = JSONObject(chapterResponse.body!!.string()).get("results").toString()

        // Decrypt chapter JSON
        val chapterJsonString = decryptChapterData(disposableData, disposablePass)

        val chapterJson = JSONObject(chapterJsonString)
        // Get the comic path word
        val comicPathWord = chapterJson.optJSONObject("build")?.optString("path_word")

        // Get chapter groups
        val chapterGroups = chapterJson.optJSONObject("groups")
        if (chapterGroups == null) {
            return listOf()
        }

        val retChapter = ArrayList<SChapter>()
        // Get chapters according to groups
        val keys = chapterGroups.keys().asSequence().toList()
        keys.filter { it -> it == "default" }.forEach { groupName ->
            run {
                val chapterGroup = chapterGroups.getJSONObject(groupName)
                fillChapters(chapterGroup, retChapter, comicPathWord)
            }
        }

        val otherChapters = ArrayList<SChapter>()
        keys.filter { it -> it != "default" }.forEach { groupName ->
            run {
                val chapterGroup = chapterGroups.getJSONObject(groupName)
                fillChapters(chapterGroup, otherChapters, comicPathWord)
            }
        }

        // place others to top, as other group updates not so often
        retChapter.addAll(0, otherChapters)
        return retChapter.asReversed().apply {
            if (!isNewDateLogic) return@apply
            val latestDate = document.selectFirst(".comicParticulars-sigezi + .comicParticulars-right-txt").text()
                .let { DATE_FORMAT.parse(it)?.time ?: 0L }
            this.firstOrNull()?.date_upload = latestDate
        }
    }

    override fun pageListRequest(chapter: SChapter) = GET("$apiUrl/api/v3${chapter.url}", headers)
    override fun pageListParse(response: Response): List<Page> {
        val jsonObject = JSONObject(response.body!!.string())
        val pageArray = jsonObject.getJSONObject("results").getJSONObject("chapter").getJSONArray("contents")
        val ret = ArrayList<Page>(pageArray.length())
        for (i in 0 until pageArray.length()) {
            val page = pageArray.getJSONObject(i).getString("url")
            ret.add(Page(i, "", page))
        }

        return ret
    }

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", String.format(USER_AGENT, preferences.getString(CHROME_VERSION_PREF, CHROME_VERSION_DEFAULT)))
        .add("region", if (preferences.getBoolean(CHANGE_CDN_OVERSEAS, false)) "0" else "1")

    // Unused, we can get image urls directly from the chapter page
    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("This method should not be called!")

    // Copymanga has different logic in polular and search page, mix two logic in search progress for now
    override fun getFilterList() = FilterList(
        MangaFilter(
            "题材",
            "theme",
            arrayOf(
                Pair("全部", ""),
                Pair("愛情", "aiqing"),
                Pair("歡樂向", "huanlexiang"),
                Pair("冒险", "maoxian"),
                Pair("百合", "baihe"),
                Pair("東方", "dongfang"),
                Pair("奇幻", "qihuan"),
                Pair("校园", "xiaoyuan"),
                Pair("科幻", "kehuan"),
                Pair("生活", "shenghuo"),
                Pair("轻小说", "qingxiaoshuo"),
                Pair("格鬥", "gedou"),
                Pair("神鬼", "shengui"),
                Pair("悬疑", "xuanyi"),
                Pair("耽美", "danmei"),
                Pair("其他", "qita"),
                Pair("舰娘", "jianniang"),
                Pair("职场", "zhichang"),
                Pair("治愈", "zhiyu"),
                Pair("萌系", "mengxi"),
                Pair("四格", "sige"),
                Pair("伪娘", "weiniang"),
                Pair("竞技", "jingji"),
                Pair("搞笑", "gaoxiao"),
                Pair("長條", "changtiao"),
                Pair("性转换", "xingzhuanhuan"),
                Pair("侦探", "zhentan"),
                Pair("节操", "jiecao"),
                Pair("热血", "rexue"),
                Pair("美食", "meishi"),
                Pair("後宮", "hougong"),
                Pair("励志", "lizhi"),
                Pair("音乐舞蹈", "yinyuewudao"),
                Pair("彩色", "COLOR"),
                Pair("AA", "aa"),
                Pair("异世界", "yishijie"),
                Pair("历史", "lishi"),
                Pair("战争", "zhanzheng"),
                Pair("机战", "jizhan"),
                Pair("C97", "comiket97"),
                Pair("C96", "comiket96"),
                Pair("宅系", "zhaixi"),
                Pair("C98", "C98"),
                Pair("C95", "comiket95"),
                Pair("恐怖", "%E6%81%90%E6%80 %96"),
                Pair("FATE", "fate"),
                Pair("無修正", "Uncensored"),
                Pair("穿越", "chuanyue"),
                Pair("武侠", "wuxia"),
                Pair("生存", "shengcun"),
                Pair("惊悚", "jingsong"),
                Pair("都市", "dushi"),
                Pair("LoveLive", "loveLive"),
                Pair("转生", "zhuansheng"),
                Pair("重生", "chongsheng"),
                Pair("仙侠", "xianxia")
            )
        ),
        MangaFilter(
            "排序",
            "ordering",
            arrayOf(
                Pair("最热门", "-popular"),
                Pair("最冷门", "popular"),
                Pair("最新", "-datetime_updated"),
                Pair("最早", "datetime_updated"),
            )
        ),
    )

    private class MangaFilter(
        displayName: String,
        searchName: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0
    ) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        val searchName = searchName
        fun toUriPart(): String {
            val selectVal = vals[state].second
            return if (selectVal != "") "$searchName=$selectVal" else ""
        }
    }

    private fun parseSearchMangaWithFilterOrPopularOrLatestResponse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("div.exemptComicList div.exemptComic-box").first().attr("list")

        val comicArray = JSONArray(mangas)

        // There is always a next pager, so use itemCount to check. XD
        val hasNextPage = comicArray.length() == popularLatestPageSize
        val ret = mangaListFromJsonArray(comicArray)

        return MangasPage(ret, hasNextPage)
    }

    private fun parseSearchMangaResponseAsJson(response: Response): MangasPage {
        val body = response.body!!.string()
        // results > comic > list []
        val res = JSONObject(body)
        val comicArray = res.optJSONObject("results")?.optJSONArray("list")
        if (comicArray == null) {
            return MangasPage(listOf(), false)
        }

        val ret = mangaListFromJsonArray(comicArray)

        return MangasPage(ret, comicArray.length() == searchPageSize)
    }

    private fun mangaListFromJsonArray(comicArray: JSONArray): ArrayList<SManga> {
        val ret = ArrayList<SManga>(comicArray.length())

        for (i in 0 until comicArray.length()) {
            val obj = comicArray.getJSONObject(i)
            val authorArray = obj.getJSONArray("author")
            var _title: String = obj.getString("name")
            if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
                _title = ChineseUtils.toSimplified(_title)
            }
            ret.add(
                SManga.create().apply {
                    title = _title
                    var picture = obj.getString("cover")
                    if (preferences.getBoolean(CHANGE_CDN_OVERSEAS, false)) {
                        picture = replaceToMirror2.replace(picture, "mirror2.mangafunc.fun:443")
                        picture = replaceToMirror.replace(picture, "mirror.mangafunc.fun:443")
                    }
                    thumbnail_url = picture
                    author = Array<String?>(authorArray.length()) { i -> authorArray.getJSONObject(i).getString("name") }.joinToString(", ")
                    status = SManga.UNKNOWN
                    url = "/comic/${obj.getString("path_word")}"
                }
            )
        }

        return ret
    }

    private fun fillChapters(chapterGroup: JSONObject, retChapter: ArrayList<SChapter>, comicPathWord: String?) {
        // group's last update time
        val groupLastUpdateTime =
            chapterGroup.optJSONObject("last_chapter")?.optString("datetime_created")

        // chapters in the group to
        val chapterArray = chapterGroup.optJSONArray("chapters")
        if (chapterArray != null) {
            for (i in 0 until chapterArray.length()) {
                val chapter = chapterArray.getJSONObject(i)
                retChapter.add(
                    SChapter.create().apply {
                        name = chapter.getString("name")
                        url = "/comic/$comicPathWord/chapter/${chapter.getString("id")}"
                        date_upload = stringToUnixTimestamp(groupLastUpdateTime)
                    }
                )
            }
        }
    }

    private fun hexStringToByteArray(string: String): ByteArray {
        val bytes = ByteArray(string.length / 2)
        for (i in 0 until string.length / 2) {
            bytes[i] = string.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun stringToUnixTimestamp(string: String?, pattern: String = "yyyy-MM-dd", locale: Locale = Locale.CHINA): Long {
        if (string == null) System.currentTimeMillis()

        return try {
            val time = SimpleDateFormat(pattern, locale).parse(string)?.time
            if (time != null) time else System.currentTimeMillis()
        } catch (ex: Exception) {
            // Set the time to current in order to display the updated manga in the "Recent updates" section
            System.currentTimeMillis()
        }
    }

    // thanks to unpacker toolsite, http://matthewfl.com/unPacker.html
    private fun decryptChapterData(disposableData: String, disposablePass: String?): String {
        val prePart = disposableData.substring(0, 16)
        val postPart = disposableData.substring(16, disposableData.length)
        val disposablePassByteArray = (disposablePass ?: "hotmanga.aes.key").toByteArray(Charsets.UTF_8)
        val prepartByteArray = prePart.toByteArray(Charsets.UTF_8)
        val dataByteArray = hexStringToByteArray(postPart)

        val secretKey = SecretKeySpec(disposablePassByteArray, "AES")
        val iv = IvParameterSpec(prepartByteArray)
        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, iv)
        val result = String(cipher.doFinal(dataByteArray), Charsets.UTF_8)

        return result
    }

    // Change Title to Simplified Chinese For Library Gobal Search Optionally
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val zhPreference = androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_Simplified_Chinese_TITLE_PREF
            title = "将标题转换为简体中文"
            summary = "需要重启软件以生效。已添加漫画需要迁移改变标题。"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(SHOW_Simplified_Chinese_TITLE_PREF, newValue as Boolean).commit()
            }
        }
        val cdnPreference = androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = CHANGE_CDN_OVERSEAS
            title = "转换图片CDN为境外CDN"
            summary = "需要重启软件（及清除章节缓存）以生效。加载图片使用境外CDN，使用代理的情况下推荐打开此选项（境外CDN可能无法查看一些刚刚更新的漫画，需要等待资源更新到CDN）"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(CHANGE_CDN_OVERSEAS, newValue as Boolean).commit()
            }
        }
        val chromeVersionPreference = androidx.preference.EditTextPreference(screen.context).apply {
            key = CHROME_VERSION_PREF
            title = "User Agent 中的 Chrome 版本号"
            summary = "访问出现异常时，可以尝试输入最新的 Chrome 版本号。重启生效。"
            setDefaultValue(CHROME_VERSION_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(CHROME_VERSION_PREF, newValue as String).apply()
                true
            }
        }
        screen.addPreference(zhPreference)
        screen.addPreference(cdnPreference)
        screen.addPreference(chromeVersionPreference)
    }

    companion object {
        private const val SHOW_Simplified_Chinese_TITLE_PREF = "showSCTitle"
        private const val CHANGE_CDN_OVERSEAS = "changeCDN"
        private const val CHROME_VERSION_PREF = "chromeVersion"
        private const val CHROME_VERSION_DEFAULT = "103"

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%s.0.0.0 Safari/537.36"

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        private val isNewDateLogic = AppInfo.getVersionCode() >= 81
    }
}
