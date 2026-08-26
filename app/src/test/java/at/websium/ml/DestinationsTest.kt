package at.websium.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved destination set and its stored form.
 *
 * The invariant every case here circles is that the active id names an entry or is null. The
 * store trusts it, the stream toggle arms whatever it points at, and a selection left pointing at
 * a deleted entry would arm nothing while the app said otherwise.
 */
class DestinationsTest {

    /** stands in for the string resource the app passes when nothing else names a destination */
    private val UNNAMED = "Destination"

    private val twitch = Destination("a", "Twitch live", "rtmp://live.twitch.tv/app/KEY")
    private val inspector = Destination("b", "Twitch Inspector", "rtmp://live.twitch.tv/app/K?bandwidthtest=true")
    private val local = Destination("c", "Local", "rtmp://mediamtx.local/live/KEY")

    // adding and selecting

    @Test
    fun startsEmptyWithNothingActive() {
        assertEquals(emptyList<Destination>(), Destinations().entries)
        assertNull(Destinations().active)
    }

    @Test
    fun theFirstDestinationAddedBecomesTheActiveOne() {
        val set = Destinations().with(twitch)
        assertEquals(twitch, set.active)
    }

    @Test
    fun addingAnotherLeavesTheSelectionAlone() {
        val set = Destinations().with(twitch).with(inspector).with(local)
        assertEquals(listOf(twitch, inspector, local), set.entries)
        assertEquals(twitch, set.active)
    }

    @Test
    fun anEntryIsReplacedInPlaceRatherThanAppended() {
        val renamed = twitch.copy(label = "Twitch")
        val set = Destinations().with(twitch).with(inspector).with(renamed)

        assertEquals(listOf(renamed, inspector), set.entries)
        assertEquals("Twitch", set.active?.label)
    }

    @Test
    fun selectingAnEntryMakesItActive() {
        val set = Destinations().with(twitch).with(inspector).withActive("b")
        assertEquals(inspector, set.active)
    }

    @Test
    fun selectingSomethingThatIsNotThereChangesNothing() {
        val set = Destinations().with(twitch).withActive("nope")
        assertEquals(twitch, set.active)
    }

    // deleting

    @Test
    fun deletingTheActiveEntryMovesTheSelectionToWhatIsLeft() {
        val set = Destinations().with(twitch).with(inspector).without("a")

        assertEquals(listOf(inspector), set.entries)
        assertEquals(inspector, set.active)
    }

    @Test
    fun deletingSomethingElseLeavesTheSelectionAlone() {
        val set = Destinations().with(twitch).with(inspector).withActive("b").without("a")
        assertEquals(inspector, set.active)
    }

    @Test
    fun deletingTheLastEntryLeavesNothingActive() {
        val set = Destinations().with(twitch).without("a")

        assertTrue(set.entries.isEmpty())
        assertNull(set.active)
        assertNull(set.activeId)
    }

    // the stored form

    @Test
    fun aSetSurvivesARoundTrip() {
        val set = Destinations().with(twitch).with(inspector).with(local).withActive("c")
        assertEquals(set, Destinations.decode(set.encode()))
    }

    @Test
    fun anEmptySetSurvivesARoundTrip() {
        assertEquals(Destinations(), Destinations.decode(Destinations().encode()))
    }

    @Test
    fun nothingStoredReadsBackAsAnEmptySet() {
        assertEquals(Destinations(), Destinations.decode(null))
        assertEquals(Destinations(), Destinations.decode(""))
    }

    @Test
    fun anUnreadableLineIsSkippedRatherThanFailingTheRead() {
        val set = Destinations.decode("a\na\tTwitch live\trtmp://live.twitch.tv/app/KEY\ngarbage")
        assertEquals(listOf(twitch), set.entries)
    }

    @Test
    fun anActiveIdNamingNothingFallsBackToTheFirstEntry() {
        val set = Destinations.decode("gone\na\tTwitch live\trtmp://live.twitch.tv/app/KEY")
        assertEquals(twitch, set.active)
    }

    @Test
    fun aLabelCarryingTheFieldSeparatorsCannotBreakTheStoredForm() {
        val awkward = Destination.of("a", "Twitch\tlive\nagain", "rtmp://live.twitch.tv/app/KEY", UNNAMED)
        val set = Destinations().with(awkward)

        assertEquals("Twitch live again", awkward.label)
        assertEquals(set, Destinations.decode(set.encode()))
    }

    // naming

    @Test
    fun anUnnamedDestinationIsNamedAfterItsHost() {
        val entry = Destination.of("a", "   ", "rtmp://live.twitch.tv/app/KEY", UNNAMED)
        assertEquals("live.twitch.tv", entry.label)
    }

    @Test
    fun aUrlWithNoHostFallsBackToTheNameTheCallerSupplies() {
        assertEquals(UNNAMED, Destination.of("a", "", "nonsense", UNNAMED).label)
    }

    // carrying the old single destination forward

    @Test
    fun theSavedDestinationBecomesTheFirstEntryAndIsActive() {
        val set = Destinations.fromLegacy("a", "  rtmp://live.twitch.tv/app/KEY  ", UNNAMED)

        assertEquals(1, set.entries.size)
        assertEquals("rtmp://live.twitch.tv/app/KEY", set.active?.url)
        assertEquals("live.twitch.tv", set.active?.label)
    }

    @Test
    fun nothingSavedCarriesForwardAsAnEmptySet() {
        assertEquals(Destinations(), Destinations.fromLegacy("a", null, UNNAMED))
        assertEquals(Destinations(), Destinations.fromLegacy("a", "   ", UNNAMED))
    }
}
