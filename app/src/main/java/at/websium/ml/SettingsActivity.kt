package at.websium.ml

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            /*
             * The destination's last path segment is a stream key. Show it masked, so the value
             * is recognisable enough to confirm without being readable over a shoulder or in a
             * screenshot attached to a bug report.
             */
            val destination = findPreference<EditTextPreference>(getString(R.string.pref_rtmp_key))
            destination?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                val stored = preference.text
                if (stored.isNullOrBlank()) {
                    getString(R.string.pref_rtmp_unset)
                } else {
                    redactStreamKey(stored)
                }
            }
        }
    }
}
