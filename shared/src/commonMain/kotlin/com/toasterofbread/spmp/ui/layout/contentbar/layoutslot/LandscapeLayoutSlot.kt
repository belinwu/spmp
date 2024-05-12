package com.toasterofbread.spmp.ui.layout.contentbar.layoutslot

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import dev.toastbits.composekit.settings.ui.Theme
import com.toasterofbread.spmp.resources.getString
import com.toasterofbread.spmp.ui.layout.contentbar.*
import com.toasterofbread.spmp.platform.AppContext
import dev.toastbits.composekit.platform.PreferencesProperty
import kotlinx.serialization.json.*

enum class LandscapeLayoutSlot: LayoutSlot {
    OUTER_SIDE_LEFT,
    INNER_SIDE_LEFT,
    OUTER_SIDE_RIGHT,
    INNER_SIDE_RIGHT,
    UPPER_TOP_BAR,
    LOWER_TOP_BAR,
    ABOVE_PLAYER,
    BELOW_PLAYER,
    PLAYER_BOTTOM_START,
    PLAYER_BOTTOM_END;

    override val is_vertical: Boolean get() =
        when (this) {
            OUTER_SIDE_LEFT,
            INNER_SIDE_LEFT,
            OUTER_SIDE_RIGHT,
            INNER_SIDE_RIGHT -> true
            else -> false
        }

    override val is_start: Boolean get() =
        when (this) {
            OUTER_SIDE_LEFT -> true
            INNER_SIDE_LEFT -> true
            OUTER_SIDE_RIGHT -> false
            INNER_SIDE_RIGHT -> false
            UPPER_TOP_BAR -> true
            LOWER_TOP_BAR -> true
            ABOVE_PLAYER -> false
            BELOW_PLAYER -> false
            PLAYER_BOTTOM_START -> true
            PLAYER_BOTTOM_END -> false
        }

    override fun getSlotsProperty(context: AppContext): PreferencesProperty<Map<String, ContentBarReference?>> =
        context.settings.layout.LANDSCAPE_SLOTS

    override fun getKey(): String = name

    override fun getName(): String =
        when (this) {
            OUTER_SIDE_LEFT -> getString("layout_slot_landscape_outer_side_left")
            INNER_SIDE_LEFT -> getString("layout_slot_landscape_inner_side_left")
            OUTER_SIDE_RIGHT -> getString("layout_slot_landscape_outer_side_right")
            INNER_SIDE_RIGHT -> getString("layout_slot_landscape_inner_side_right")
            UPPER_TOP_BAR -> getString("layout_slot_landscape_upper_top_bar")
            LOWER_TOP_BAR -> getString("layout_slot_landscape_lower_top_bar")
            ABOVE_PLAYER -> getString("layout_slot_landscape_above_player")
            BELOW_PLAYER -> getString("layout_slot_landscape_below_player")
            PLAYER_BOTTOM_START -> getString("layout_slot_landscape_player_bottom_start")
            PLAYER_BOTTOM_END -> getString("layout_slot_landscape_player_bottom_end")
        }

    override fun getDefaultContentBar(): ContentBar? =
        when (this) {
            OUTER_SIDE_LEFT -> CustomContentBarTemplate.NAVIGATION.getContentBar()
            INNER_SIDE_LEFT -> InternalContentBar.PRIMARY
            OUTER_SIDE_RIGHT -> null
            INNER_SIDE_RIGHT -> null

            UPPER_TOP_BAR -> null
            LOWER_TOP_BAR -> null

            ABOVE_PLAYER -> InternalContentBar.SECONDARY
            BELOW_PLAYER -> null

            PLAYER_BOTTOM_START -> CustomContentBarTemplate.SONG_ACTIONS.getContentBar()
            PLAYER_BOTTOM_END -> CustomContentBarTemplate.LYRICS.getContentBar()
        }

    override fun getDefaultBackgroundColour(theme: Theme): ColourSource =
        when (this) {
            OUTER_SIDE_LEFT -> ThemeColourSource(Theme.Colour.CARD)
            INNER_SIDE_LEFT -> ThemeColourSource(Theme.Colour.CARD)
            OUTER_SIDE_RIGHT -> ThemeColourSource(Theme.Colour.CARD)
            INNER_SIDE_RIGHT -> ThemeColourSource(Theme.Colour.CARD)

            UPPER_TOP_BAR -> ThemeColourSource(Theme.Colour.CARD)
            LOWER_TOP_BAR -> ThemeColourSource(Theme.Colour.CARD)

            ABOVE_PLAYER -> CustomColourSource(Color.Transparent)
            BELOW_PLAYER -> ThemeColourSource(Theme.Colour.CARD)

            PLAYER_BOTTOM_START -> CustomColourSource(Color.Transparent)
            PLAYER_BOTTOM_END -> CustomColourSource(Color.Transparent)
        }

    override fun hasConfig(): Boolean =
        this == BELOW_PLAYER

    @Composable
    override fun ConfigurationItems(
        config_data: JsonElement?,
        item_modifier: Modifier,
        onModification: (JsonElement?) -> Unit
    ) {
        if (this != BELOW_PLAYER) {
            return
        }

        BelowPlayerConfigurationItems(this, config_data, item_modifier, onModification)
    }
}
