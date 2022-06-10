package eu.kanade.tachiyomi.extension.ru.mintmanga

import eu.kanade.tachiyomi.multisrc.grouple.GroupLe
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

class MintManga : GroupLe("MintManga", "https://mintmanga.live", "ru"){

    override val id: Long = 6

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search/advanced".toHttpUrlOrNull()!!.newBuilder()
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is GenreList -> filter.state.forEach { genre ->
                    if (genre.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(genre.id, arrayOf("=", "=in", "=ex")[genre.state])
                    }
                }
                is Category -> filter.state.forEach { category ->
                    if (category.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(category.id, arrayOf("=", "=in", "=ex")[category.state])
                    }
                }
                is AgeList -> filter.state.forEach { age ->
                    if (age.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(age.id, arrayOf("=", "=in", "=ex")[age.state])
                    }
                }
                is More -> filter.state.forEach { more ->
                    if (more.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(more.id, arrayOf("=", "=in", "=ex")[more.state])
                    }
                }
                is FilList -> filter.state.forEach { fils ->
                    if (fils.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(fils.id, arrayOf("=", "=in", "=ex")[fils.state])
                    }
                }
                is OrderBy -> {
                    if (filter.state > 0) {
                        val ord = arrayOf("not", "year", "rate", "popularity", "votes", "created", "updated")[filter.state]
                        val ordUrl = "$baseUrl/list?sortType=$ord&offset=${70 * (page - 1)}".toHttpUrlOrNull()!!.newBuilder()
                        return GET(ordUrl.toString(), headers)
                    }
                }
                else -> return@forEach
            }
        }
        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }
        return if (url.toString().contains("?"))
            GET(url.toString().replace("=%3D", "="), headers)
        else  popularMangaRequest(page)
    }

    private class OrderBy : Filter.Select<String>(
        "Сортировка (только)",
        arrayOf("Без сортировки", "По году", "По популярности", "Популярно сейчас", "По рейтингу", "Новинки", "По дате обновления")
    )

    private class Genre(name: String, val id: String) : Filter.TriState(name)

    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Жанры", genres)
    private class Category(categories: List<Genre>) : Filter.Group<Genre>("Категории", categories)
    private class AgeList(ages: List<Genre>) : Filter.Group<Genre>("Возрастная рекомендация", ages)
    private class More(moren: List<Genre>) : Filter.Group<Genre>("Прочее", moren)
    private class FilList(fils: List<Genre>) : Filter.Group<Genre>("Фильтры", fils)

    override fun getFilterList() = FilterList(
        OrderBy(),
        Category(getCategoryList()),
        GenreList(getGenreList()),
        AgeList(getAgeList()),
        More(getMore()),
        FilList(getFilList())
    )
    private fun getFilList() = listOf(
        Genre("Высокий рейтинг", "s_high_rate"),
        Genre("Сингл", "s_single"),
        Genre("Для взрослых", "s_mature"),
        Genre("Завершенная", "s_completed"),
        Genre("Переведено", "s_translated"),
        Genre("Длинная", "s_many_chapters"),
        Genre("Ожидает загрузки", "s_wait_upload"),
    )
    private fun getMore() = listOf(
        Genre("В цвете", "el_4614"),
        Genre("Веб", "el_1355"),
        Genre("Выпуск приостановлен", "el_5232"),
        Genre("Не Яой", "el_1874"),
        Genre("Сборник", "el_1348")
    )

    private fun getAgeList() = listOf(
        Genre("R(16+)", "el_3968"),
        Genre("NC-17(18+)", "el_3969"),
        Genre("R18+(18+)", "el_3990")
    )

    private fun getCategoryList() = listOf(
        Genre("Ёнкома", "el_2741"),
        Genre("Комикс западный", "el_1903"),
        Genre("Комикс русский", "el_2173"),
        Genre("Манхва", "el_1873"),
        Genre("Маньхуа", "el_1875"),
        Genre("Ранобэ", "el_5688"),
    )

    private fun getGenreList() = listOf(
        Genre("арт", "el_2220"),
        Genre("бара", "el_1353"),
        Genre("боевик", "el_1346"),
        Genre("боевые искусства", "el_1334"),
        Genre("вампиры", "el_1339"),
        Genre("гарем", "el_1333"),
        Genre("гендерная интрига", "el_1347"),
        Genre("героическое фэнтези", "el_1337"),
        Genre("детектив", "el_1343"),
        Genre("дзёсэй", "el_1349"),
        Genre("додзинси", "el_1332"),
        Genre("драма", "el_1310"),
        Genre("игра", "el_5229"),
        Genre("история", "el_1311"),
        Genre("киберпанк", "el_1351"),
        Genre("комедия", "el_1328"),
        Genre("меха", "el_1318"),
        Genre("научная фантастика", "el_1325"),
        Genre("омегаверс", "el_5676"),
        Genre("повседневность", "el_1327"),
        Genre("постапокалиптика", "el_1342"),
        Genre("приключения", "el_1322"),
        Genre("психология", "el_1335"),
        Genre("романтика", "el_1313"),
        Genre("самурайский боевик", "el_1316"),
        Genre("сверхъестественное", "el_1350"),
        Genre("сёдзё", "el_1314"),
        Genre("сёдзё-ай", "el_1320"),
        Genre("сёнэн", "el_1326"),
        Genre("сёнэн-ай", "el_1330"),
        Genre("спорт", "el_1321"),
        Genre("сэйнэн", "el_1329"),
        Genre("трагедия", "el_1344"),
        Genre("триллер", "el_1341"),
        Genre("ужасы", "el_1317"),
        Genre("фэнтези", "el_1323"),
        Genre("школа", "el_1319"),
        Genre("эротика", "el_1340"),
        Genre("этти", "el_1354"),
        Genre("юри", "el_1315"),
        Genre("яой", "el_1336")
    )
}
