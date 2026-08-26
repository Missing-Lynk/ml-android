package at.websium.ml

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/**
 * Manage the saved streaming destinations: add one, edit or delete it, and pick which one the
 * stream toggle arms.
 *
 * Every change is written straight through [DestinationStore] and the screen redrawn from what
 * was stored, so what is on screen is what a later session will read. The rows are inflated into
 * a plain column rather than recycled: this is a handful of entries, and a list that never
 * scrolls past a screenful has nothing to recycle.
 */
class DestinationsActivity : AppCompatActivity() {

    private lateinit var store: DestinationStore
    private lateinit var rows: LinearLayout
    private lateinit var empty: TextView

    /** the set as last read and drawn, which every edit is applied to */
    private var destinations = Destinations()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_destinations)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = DestinationStore(this)
        rows = findViewById(R.id.destinations_list)
        empty = findViewById(R.id.destinations_empty)

        render()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.destinations_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_add) {
            edit(null)
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        destinations = store.read()

        rows.removeAllViews()
        destinations.entries.forEach { entry -> rows.addView(rowFor(entry)) }

        val isEmpty = destinations.entries.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun rowFor(entry: Destination): View {
        val row = layoutInflater.inflate(R.layout.item_destination, rows, false)

        val radio = row.findViewById<RadioButton>(R.id.destination_active)
        radio.isChecked = entry.id == destinations.activeId
        radio.contentDescription = getString(R.string.destination_select, entry.label)

        row.findViewById<TextView>(R.id.destination_label).text = entry.label
        row.findViewById<TextView>(R.id.destination_url).text = redactStreamKey(entry.url)

        row.setOnClickListener { save(destinations.withActive(entry.id)) }
        radio.setOnClickListener { save(destinations.withActive(entry.id)) }

        val more = row.findViewById<ImageButton>(R.id.destination_more)
        more.contentDescription = getString(R.string.destination_more, entry.label)
        more.setOnClickListener { showRowMenu(more, entry) }

        return row
    }

    private fun save(next: Destinations) {
        store.write(next)
        render()
    }

    private fun showRowMenu(anchor: View, entry: Destination) {
        val menu = PopupMenu(this, anchor)
        menu.menuInflater.inflate(R.menu.destination_row_menu, menu.menu)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> {
                    edit(entry); true
                }

                R.id.action_delete -> {
                    confirmDelete(entry); true
                }

                else -> false
            }
        }
        menu.show()
    }

    private fun confirmDelete(entry: Destination) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.destination_delete_confirm, entry.label))
            .setNegativeButton(R.string.destination_cancel, null)
            .setPositiveButton(R.string.destination_delete) { _, _ ->
                save(destinations.without(entry.id))
            }
            .show()
    }

    /**
     * The add and edit dialog. A null [entry] adds one, and an id is minted for it on save.
     */
    private fun edit(entry: Destination?) {
        val view = layoutInflater.inflate(R.layout.dialog_destination, null)
        val label = view.findViewById<TextInputEditText>(R.id.destination_label_input)
        val url = view.findViewById<TextInputEditText>(R.id.destination_url_input)
        label.setText(entry?.label.orEmpty())
        url.setText(entry?.url.orEmpty())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (entry == null) R.string.destinations_add else R.string.destination_edit)
            .setView(view)
            .setNegativeButton(R.string.destination_cancel, null)
            .setPositiveButton(R.string.destination_save, null)
            .create()

        dialog.show()

        /*
         * The click listener is set after show() rather than passed to the builder, because a
         * button given one through the builder dismisses the dialog whatever the listener does,
         * which would throw away what the user typed when the URL is rejected.
         */
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = url.text.toString()
            if (!isRestreamUrl(typed)) {
                Toast.makeText(this, R.string.destination_needs_url, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val id = entry?.id ?: store.newId()
            val named = Destination.of(id, label.text.toString(), typed, store.unnamed)
            save(destinations.with(named))
            dialog.dismiss()
        }
    }
}
