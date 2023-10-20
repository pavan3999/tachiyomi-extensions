package eu.kanade.tachiyomi.extension.ar.mangalek

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.multisrc.madara.Madara
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Mangalek : Madara("مانجا ليك", "https://manga-lek.net", "ar", SimpleDateFormat("MMMM dd, yyyy", Locale("ar"))) {

    override val chapterUrlSuffix = ""

    private val defaultBaseUrl = "https://manga-lek.net"
    override val baseUrl by lazy { getPrefBaseUrl() }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val RESTART_TACHIYOMI = ".لتطبيق الإعدادات الجديدة Tachiyomi أعد تشغيل"
        private const val BASE_URL_PREF_TITLE = "تعديل الرابط"
        private const val BASE_URL_PREF = "overrideBaseUrl_v${BuildConfig.VERSION_CODE}"
        private const val BASE_URL_PREF_SUMMARY = ".للاستخدام المؤقت. تحديث التطبيق سيؤدي الى حذف الإعدادات"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            this.setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_TACHIYOMI, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)

        super.setupPreferenceScreen(screen)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!
}
