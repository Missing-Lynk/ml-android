package at.websium.ml

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
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
        private var audio: ListPreference? = null

        /**
         * Granting turns the setting the user just tried to make; refusing leaves it on silence
         * and says why, so the screen never claims a source the broadcast cannot use.
         */
        private val microphonePermission =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    audio?.value = MICROPHONE_VALUE
                } else {
                    Toast.makeText(
                        requireContext(), R.string.audio_needs_permission, Toast.LENGTH_LONG
                    ).show()
                }
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            destinations = findPreference(getString(R.string.pref_destinations_key))
            destinations?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DestinationsActivity::class.java))
                true
            }

            /*
             * Choosing the microphone is the moment the request makes sense, rather than the
             * moment a broadcast is armed: the setting is what the user is thinking about here,
             * and arming happens on the video screen with a control that hides itself.
             */
            audio = findPreference(getString(R.string.pref_audio_key))
            audio?.setOnPreferenceChangeListener { _, chosen ->
                if (chosen != MICROPHONE_VALUE || isMicrophoneGranted()) {
                    return@setOnPreferenceChangeListener true
                }
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                false
            }
        }

        private fun isMicrophoneGranted(): Boolean {
            return ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
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
