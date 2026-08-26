package at.websium.ml

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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

        private var destinations: Preference? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            destinations = findPreference(getString(R.string.pref_destinations_key))
            destinations?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DestinationsActivity::class.java))
                true
            }
        }

        /**
         * The active destination's name, refreshed here because it can change on the screen this
         * row opens. The name rather than the URL: the URL ends in a stream key.
         */
        override fun onResume() {
            super.onResume()
            val active = DestinationStore(requireContext()).read().active
            destinations?.summary = active?.label ?: getString(R.string.pref_rtmp_unset)
        }
    }
}
