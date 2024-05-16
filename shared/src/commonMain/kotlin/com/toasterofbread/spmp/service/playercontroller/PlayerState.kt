package com.toasterofbread.spmp.service.playercontroller

import LocalPlayerState
import SpMp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import dev.toastbits.composekit.platform.PlatformPreferences
import dev.toastbits.composekit.platform.PlatformPreferencesListener
import dev.toastbits.composekit.platform.composable.BackHandler
import dev.toastbits.composekit.platform.composable.composeScope
import dev.toastbits.composekit.settings.ui.Theme
import dev.toastbits.composekit.utils.composable.getEnd
import dev.toastbits.composekit.utils.composable.getStart
import com.toasterofbread.spmp.db.Database
import com.toasterofbread.spmp.model.mediaitem.MediaItem
import com.toasterofbread.spmp.model.mediaitem.artist.Artist
import com.toasterofbread.spmp.model.mediaitem.playlist.Playlist
import com.toasterofbread.spmp.model.mediaitem.song.Song
import com.toasterofbread.spmp.model.settings.Settings
import com.toasterofbread.spmp.platform.AppContext
import com.toasterofbread.spmp.platform.FormFactor
import com.toasterofbread.spmp.platform.download.DownloadMethodSelectionDialog
import com.toasterofbread.spmp.platform.download.DownloadStatus
import com.toasterofbread.spmp.platform.playerservice.PlayerService
import com.toasterofbread.spmp.platform.playerservice.PlayerServicePlayer
import com.toasterofbread.spmp.platform.playerservice.PlayerServiceLoadState
import com.toasterofbread.spmp.platform.playerservice.PlayerServiceCompanion
import com.toasterofbread.spmp.platform.playerservice.PlatformInternalPlayerService
import com.toasterofbread.spmp.platform.playerservice.PlatformExternalPlayerService
import com.toasterofbread.spmp.ui.component.longpressmenu.LongPressMenu
import com.toasterofbread.spmp.ui.component.longpressmenu.LongPressMenuData
import com.toasterofbread.spmp.ui.component.multiselect.AppPageMultiSelectContext
import com.toasterofbread.spmp.ui.component.multiselect.MediaItemMultiSelectContext
import com.toasterofbread.spmp.ui.component.multiselect.MultiSelectInfoDisplayContent
import com.toasterofbread.spmp.ui.component.multiselect.MultiSelectItem
import com.toasterofbread.spmp.ui.layout.apppage.AppPage
import com.toasterofbread.spmp.ui.layout.apppage.AppPageState
import com.toasterofbread.spmp.ui.layout.apppage.AppPageWithItem
import com.toasterofbread.spmp.ui.layout.apppage.SongAppPage
import com.toasterofbread.spmp.ui.layout.apppage.mainpage.MINIMISED_NOW_PLAYING_HEIGHT_DP
import com.toasterofbread.spmp.ui.layout.apppage.mainpage.MainPageDisplay
import com.toasterofbread.spmp.ui.layout.artistpage.ArtistAppPage
import com.toasterofbread.spmp.ui.layout.nowplaying.PlayerExpansionState
import com.toasterofbread.spmp.ui.layout.nowplaying.ThemeMode
import com.toasterofbread.spmp.ui.layout.nowplaying.getNPBackground
import com.toasterofbread.spmp.ui.layout.nowplaying.overlay.PlayerOverlayMenu
import com.toasterofbread.spmp.ui.layout.nowplaying.NowPlayingTopOffsetSection
import com.toasterofbread.spmp.ui.layout.nowplaying.container.npAnchorToDp
import com.toasterofbread.spmp.ui.layout.playlistpage.PlaylistAppPage
import dev.toastbits.ytmkt.model.external.YoutubePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import com.toasterofbread.spmp.ui.layout.contentbar.layoutslot.*
import com.toasterofbread.spmp.ui.layout.contentbar.*
import com.toasterofbread.spmp.ui.layout.BarColourState
import com.toasterofbread.spmp.service.playercontroller.PlayerState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import dev.toastbits.composekit.utils.composable.getTop
import dev.toastbits.composekit.utils.composable.OnChangedEffect
import dev.toastbits.composekit.utils.common.blendWith
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.requiredWidth
import kotlin.math.roundToInt
import kotlin.math.absoluteValue
import ProgramArguments
import LocalProgramArguments

typealias DownloadRequestCallback = (DownloadStatus?) -> Unit

enum class FeedLoadState { PREINIT, NONE, LOADING, CONTINUING }

// This is an atrocity
class PlayerState(
    val context: AppContext,
    val launch_arguments: ProgramArguments,
    internal val coroutine_scope: CoroutineScope
) {
    val database: Database get() = context.database
    val settings: Settings get() = context.settings
    val theme: Theme get() = context.theme
    val app_page: AppPage get() = app_page_state.current_page

    private var _player: PlayerService? by mutableStateOf(null)

    private val app_page_undo_stack: MutableList<AppPage?> = mutableStateListOf()

    private val low_memory_listener: () -> Unit
    private val prefs_listener: PlatformPreferencesListener

    fun switchNowPlayingPage(page: Int) {
        coroutine_scope.launch {
            np_swipe_state.animateTo(page)
        }
    }

    private var long_press_menu_data: LongPressMenuData? by mutableStateOf(null)
    private var long_press_menu_showing: Boolean by mutableStateOf(false)
    private var long_press_menu_direct: Boolean by mutableStateOf(false)

    private fun createSwipeState(
        anchors: DraggableAnchors<Int> = DraggableAnchors {},
        animation_spec: AnimationSpec<Float> = tween()
    ): AnchoredDraggableState<Int> =
        AnchoredDraggableState(
            initialValue = 0,
            anchors = anchors,
            positionalThreshold = { total_distance ->
                total_distance * 0.2f
            },
            velocityThreshold = {
                1f
            },
            animationSpec = animation_spec
        )

    private var np_swipe_state: AnchoredDraggableState<Int> by mutableStateOf(createSwipeState())

    var np_bottom_bar_config: LayoutSlot.BelowPlayerConfig? by mutableStateOf(null)
    var np_bottom_bar_showing: Boolean by mutableStateOf(false)
    private var _np_bottom_bar_height: Dp by mutableStateOf(0.dp)
    var np_bottom_bar_height: Dp
        get() =
            if (!np_bottom_bar_showing) 0.dp
            else _np_bottom_bar_height
        set(value) { _np_bottom_bar_height = value }

    val form_factor: FormFactor by derivedStateOf { FormFactor.getCurrent(this) }

    private var download_request_songs: List<Song>? by mutableStateOf(null)
    private var download_request_always_show_options: Boolean by mutableStateOf(false)
    private var download_request_callback: DownloadRequestCallback? by mutableStateOf(null)

    val bar_colour_state: BarColourState =
        object : BarColourState() {
            fun getDefaultColour(): Color = theme.background

            override fun onCurrentStatusBarColourChanged(colour: Color?) {
                context.setStatusBarColour(colour ?: getDefaultColour())
            }

            override fun onCurrentNavigationBarColourChanged(colour: Color?) {
                context.setNavigationBarColour(colour ?: getDefaultColour())
            }
        }

    val expansion: PlayerExpansionState =
        object : PlayerExpansionState(this, coroutine_scope) {
            override val swipe_state: AnchoredDraggableState<Int>
                get() = np_swipe_state
        }
    var screen_size: DpSize by mutableStateOf(DpSize.Zero)

    val session_started: Boolean get() = _player?.service_player?.session_started == true
    var hide_player: Boolean by mutableStateOf(false)
    val player_showing: Boolean get() = session_started && !hide_player

    val app_page_state: AppPageState = AppPageState(this)
    val main_multiselect_context: MediaItemMultiSelectContext = AppPageMultiSelectContext(this)
    var np_theme_mode: ThemeMode by mutableStateOf(context.settings.theme.NOWPLAYING_THEME_MODE.get())

    var np_overlay_menu: PlayerOverlayMenu? by mutableStateOf(null)
    private val np_overlay_menu_queue: MutableList<PlayerOverlayMenu> = mutableListOf()

    fun navigateNpOverlayMenuBack() {
        np_overlay_menu = np_overlay_menu_queue.removeLastOrNull()
    }

    fun openNpOverlayMenu(menu: PlayerOverlayMenu?) {
        if (menu == null) {
            np_overlay_menu = null
            np_overlay_menu_queue.clear()
            return
        }

        np_overlay_menu?.also {
            np_overlay_menu_queue.add(it)
        }
        np_overlay_menu = menu
    }

    init {
        low_memory_listener = {
            if (app_page != app_page_state.SongFeed) {
                app_page_state.SongFeed.resetSongFeed()
            }
        }

        prefs_listener = PlatformPreferencesListener { _, key ->
            when (key) {
                context.settings.theme.NOWPLAYING_THEME_MODE.key -> {
                    np_theme_mode = context.settings.theme.NOWPLAYING_THEME_MODE.get()
                }
            }
        }
    }

    fun onStart() {
        SpMp.addLowMemoryListener(low_memory_listener)
        context.getPrefs().addListener(prefs_listener)

        val service_companion: PlayerServiceCompanion =
            if (!PlatformInternalPlayerService.isAvailable(context, launch_arguments) || settings.platform.ENABLE_EXTERNAL_SERVER_MODE.get())
                PlatformExternalPlayerService
            else PlatformInternalPlayerService

        if (service_companion.isServiceRunning(context)) {
            connectService(service_companion, null)
        }
        else {
            coroutine_scope.launch {
                if (PersistentQueueHandler.isPopulatedQueueSaved(context)) {
                    connectService(service_companion, null)
                }
            }
        }
    }

    fun onStop() {
        SpMp.removeLowMemoryListener(low_memory_listener)
        context.getPrefs().removeListener(prefs_listener)
    }

    fun release() {
        service_connection?.also {
            service_connection_companion?.disconnect(context, it)
        }
        service_connection = null
        service_connection_companion = null
        _player = null
    }

    fun interactService(action: (player: PlayerService) -> Unit) {
        synchronized(service_connected_listeners) {
            _player?.also {
                action(it)
                return
            }

            service_connected_listeners.add {
                action(_player!!)
            }
        }
    }

    private fun Density.getNpBottomPadding(system_insets: WindowInsets, navigation_insets: WindowInsets, keyboard_insets: WindowInsets?): Int {
        val ime_padding: Int =
            if (keyboard_insets == null || np_overlay_menu != null) 0
            else keyboard_insets.getBottom(this).let { ime ->
                if (ime > 0) {
                    val nav = navigation_insets.getBottom(this@getNpBottomPadding)
                    return@let ime.coerceAtMost(
                        (ime - nav).coerceAtLeast(0)
                    )
                }
                return@let ime
            }

        return system_insets.getBottom(this) + ime_padding
    }

    private var now_playing_top_offset_items: MutableMap<NowPlayingTopOffsetSection, MutableList<TopOffsetItem?>> = mutableStateMapOf()
    private data class TopOffsetItem(
        val height: Dp,
        val apply_spacing: Boolean = true,
        val displaying: Boolean = true
    )

    private fun getTopItemsHeight(spacing: Dp = 15.dp, filter: ((NowPlayingTopOffsetSection, Int) -> Boolean)? = null): Dp =
        now_playing_top_offset_items.entries.sumOf { items ->
            var acc: Float = 0f

            for (item in items.value.withIndex()) {
                if (item.value?.displaying != true) {
                    continue
                }

                if (filter?.invoke(items.key, item.index) == false) {
                    continue
                }

                val height: Float =
                    (item.value!!.height + (if (item.value!!.apply_spacing) spacing else 0.dp)).value

                if (items.key.isMerged()) {
                    acc = maxOf(acc, height)
                }
                else {
                    acc += height
                }
            }

            // https://youtrack.jetbrains.com/issue/KT-43310/Add-sumOf-with-Float-return-type
            return@sumOf acc.toDouble()
        }.dp

    fun getNowPlayingExpansionOffset(density: Density): Dp {
        return -np_swipe_state.offset.npAnchorToDp(density, context)
    }

    @Composable
    fun nowPlayingTopOffset(
        base: Modifier,
        section: NowPlayingTopOffsetSection,
        apply_spacing: Boolean = true,
        displaying: Boolean = true
    ): Modifier {
        val density: Density = LocalDensity.current
        val system_insets: WindowInsets = WindowInsets.systemBars
        val navigation_insets: WindowInsets = WindowInsets.navigationBars
        val keyboard_insets: WindowInsets = WindowInsets.ime

        val offset_items: MutableList<TopOffsetItem?> = remember (section) {
            now_playing_top_offset_items.getOrPut(section) {
                mutableStateListOf()
            }
        }
        val index: Int = remember(offset_items) {
            offset_items.add(null)
            return@remember offset_items.size - 1
        }

        OnChangedEffect(displaying) {
            val item: TopOffsetItem? = offset_items.getOrNull(index)
            if (item != null) {
                offset_items[index] = item.copy(
                    displaying = displaying
                )
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                offset_items[index] = null
            }
        }

        val additional_offset: Dp by animateDpAsState(
            getTopItemsHeight(filter = { item_section, item_index ->
                !section.shouldIgnoreSection(item_section)
                && (
                    item_section.ordinal > section.ordinal
                    || (item_section.ordinal == section.ordinal && item_index < index)
                )
            })
        )

        return base
            .offset {
                val bottom_padding: Int = getNpBottomPadding(system_insets, navigation_insets, keyboard_insets)
                val swipe_offset: Dp =
                    if (player_showing) -np_swipe_state.offset.npAnchorToDp(density, context) - np_bottom_bar_height// - ((screen_size.height + np_bottom_bar_height) * 0.5f)
                    else -np_bottom_bar_height

                IntOffset(
                    0,
                    swipe_offset.notUnspecified().roundToPx() - bottom_padding - additional_offset.notUnspecified().roundToPx() + 1 // Avoid single-pixel gap
                )
            }
            .padding(start = system_insets.getStart(), end = system_insets.getEnd())
            .onSizeChanged {
                with (density) {
                    offset_items[index] = TopOffsetItem(
                        height = it.height.toDp(),
                        apply_spacing = apply_spacing,
                        displaying = displaying
                    )
                }
            }
    }

    @Composable
    fun nowPlayingBottomPadding(include_np: Boolean = false, include_top_items: Boolean = include_np): Dp {
        var bottom_padding: Dp =
            with(LocalDensity.current) {
                LocalDensity.current.getNpBottomPadding(WindowInsets.systemBars, WindowInsets.navigationBars, WindowInsets.ime).toDp()
            }

        if (include_np) {
            bottom_padding += animateDpAsState(
                np_bottom_bar_height
                + (
                    if (player_showing) MINIMISED_NOW_PLAYING_HEIGHT_DP.dp
                    else 0.dp
                )
            ).value
        }
        if (include_top_items) {
            bottom_padding += animateDpAsState(
                getTopItemsHeight()
            ).value
        }

        return bottom_padding
    }

    fun openAppPage(page: AppPage?, from_current: Boolean = false, replace_current: Boolean = false) {
        if (np_swipe_state.targetValue != 0) {
            switchNowPlayingPage(0)
        }

        if (page == app_page) {
            page.onReopened()
            return
        }

        if (!replace_current) {
            app_page_undo_stack.add(app_page)
        }
        app_page_state.setPage(page, from_current = from_current, going_back = false)
        hideLongPressMenu()
    }

    fun navigateBack() {
        if (app_page.onBackNavigation()) {
            return
        }
        app_page_state.setPage(app_page_undo_stack.removeLastOrNull(), from_current = false, going_back = true)
    }

    fun clearBackHistory() {
        app_page_undo_stack.clear()
    }

    fun openMediaItem(
        item: MediaItem,
        from_current: Boolean = false,
        replace_current: Boolean = false,
        browse_params: YoutubePage.BrowseParamsData? = null
    ) {
        if (item is Artist && item.isForItem()) {
            return
        }

        val page: AppPageWithItem =
            when (item) {
                is Song ->
                    SongAppPage(app_page_state, item, browse_params)
                is Artist ->
                    ArtistAppPage(
                        app_page_state,
                        item,
                        browse_params = browse_params?.let { params ->
                            Pair(params, context.ytapi.ArtistWithParams)
                        }
                    )
                is Playlist ->
                    PlaylistAppPage(
                        app_page_state,
                        item
                    )
                else -> throw NotImplementedError(item::class.toString())
            }

        openAppPage(page, from_current, replace_current)
    }

    fun openViewMorePage(browse_id: String, title: String?) {
        openAppPage(app_page_state.getViewMorePage(browse_id, title))
    }

    fun openNowPlayingPlayerOverlayMenu(menu: PlayerOverlayMenu? = null) {
        np_overlay_menu = menu
        expansion.scrollTo(1)
    }

    fun onPlayActionOccurred() {
        if (np_swipe_state.targetValue == 0 && context.settings.behaviour.OPEN_NP_ON_SONG_PLAYED.get()) {
            switchNowPlayingPage(1)
        }
    }

    fun playMediaItem(item: MediaItem, shuffle: Boolean = false, at_index: Int = 0) {
        withPlayer {
            if (item is Song) {
                playSong(
                    item,
                    start_radio = context.settings.behaviour.START_RADIO_ON_SONG_PRESS.get(),
                    shuffle = shuffle,
                    at_index = at_index
                )
            }
            else {
                startRadioAtIndex(
                    at_index,
                    item,
                    shuffle = shuffle,
                    onSuccessfulLoad = { result ->
                        val added_songs: Int = result.songs?.size ?: return@startRadioAtIndex
                        clearQueue(from = at_index + added_songs)
                        seekToSong(at_index)
                    }
                )
            }
        }
    }

    fun playPlaylist(playlist: Playlist, from_index: Int = 0) {
        withPlayer {
            startRadioAtIndex(
                0,
                playlist,
                onSuccessfulLoad = {
                    if (from_index > 0) {
                        seekToSong(from_index)
                    }
                }
            )
        }
    }

    fun showLongPressMenu(item: MediaItem) { showLongPressMenu(LongPressMenuData(item)) }
    fun showLongPressMenu(data: LongPressMenuData) {
        long_press_menu_data = data

        if (long_press_menu_showing) {
            long_press_menu_direct = true
        }
        else {
            long_press_menu_showing = true
            long_press_menu_direct = false
        }
    }

    fun hideLongPressMenu() {
        long_press_menu_showing = false
        long_press_menu_direct = false
        long_press_menu_data = null
    }

    private var multiselect_info_display_height: Dp by mutableStateOf(0.dp)
    internal val multiselect_info_all_items_getters: MutableList<() -> List<List<MultiSelectItem>>> = mutableListOf()

    @Composable
    fun PersistentContent() {
        val form_factor: FormFactor by FormFactor.observe()

        bar_colour_state.Update()

        long_press_menu_data?.also { data ->
            LongPressMenu(
                long_press_menu_showing,
                { hideLongPressMenu() },
                data
            )
        }

        download_request_songs?.also { songs ->
            DownloadMethodSelectionDialog(
                onCancelled = {
                    download_request_songs = null
                    download_request_callback?.invoke(null)
                },
                onSelected = { method ->
                    method.execute(context, songs, download_request_callback)
                    download_request_songs = null
                },
                songs = songs,
                always_show_options = download_request_always_show_options
            )
        }

        if (form_factor.is_large) {
            val density: Density = LocalDensity.current

            AnimatedVisibility(main_multiselect_context.is_active, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().padding(15.dp)) {
                    val background_colour: Color = theme.accent

                    CompositionLocalProvider(LocalContentColor provides theme.on_accent) {
                        main_multiselect_context.MultiSelectInfoDisplayContent(
                            Modifier
                                .width(IntrinsicSize.Max)
                                .align(Alignment.BottomEnd)
                                .then(nowPlayingTopOffset(Modifier, NowPlayingTopOffsetSection.MULTISELECT))
                                .background(background_colour, MaterialTheme.shapes.small)
                                .padding(10.dp)
                                .onSizeChanged {
                                    multiselect_info_display_height = with (density) {
                                        it.height.toDp()
                                    }
                                },
                            background_colour,
                            getAllItems = {
                                multiselect_info_all_items_getters.flatMap { it() }
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun HomePage() {
        BackHandler(app_page_undo_stack.isNotEmpty()) {
            navigateBack()
        }

        val form_factor: FormFactor by FormFactor.observe()

        CompositionLocalProvider(LocalContentColor provides context.theme.on_background) {
            val bottom_padding: Dp by animateDpAsState(
                if (form_factor.is_large && main_multiselect_context.is_active) multiselect_info_display_height
                else 0.dp
            )

            MainPageDisplay(bottom_padding)
        }
    }

    val controller: PlayerService? get() = _player
    fun withPlayer(action: PlayerServicePlayer.() -> Unit) {
        _player?.also {
            action(it.service_player)
            return
        }

        connectService {
            action(it.service_player)
        }
    }

    @Composable
    fun withPlayerComposable(action: @Composable PlayerServicePlayer.() -> Unit) {
        LaunchedEffect(Unit) {
            connectService(onConnected = null)
        }

        _player?.service_player?.also {
            action(it)
        }
    }

    val service_connected: Boolean get() = _player?.load_state?.loading == false
    val service_load_state: PlayerServiceLoadState? get() = _player?.load_state
    val service_connection_error: Throwable? get() = _player?.connection_error

    private var service_connecting: Boolean = false
    private var service_connected_listeners: MutableList<(PlayerService) -> Unit> = mutableListOf()
    private var service_connection: Any? = null
    private var service_connection_companion: PlayerServiceCompanion? = null

    private fun connectService(
        service_companion: PlayerServiceCompanion = service_connection_companion!!,
        onConnected: ((PlayerService) -> Unit)?
    ) {
        synchronized(service_connected_listeners) {
            if (service_connecting) {
                if (onConnected != null) {
                    service_connected_listeners.add(onConnected)
                }
                return
            }

            _player?.also { service ->
                onConnected?.invoke(service)
                return
            }

            service_connection_companion = service_companion

            service_connecting = true
            service_connection = service_companion.connect(
                context,
                launch_arguments,
                _player,
                { service ->
                    synchronized(service_connected_listeners) {
                        _player = service
                        status.setPlayer(service)
                        service_connecting = false

                        onConnected?.invoke(service)
                        for (listener in service_connected_listeners) {
                            listener.invoke(service)
                        }
                        service_connected_listeners.clear()
                    }
                },
                {
                    service_connecting = false
                }
            )
        }
    }

    suspend fun requestServiceChange(service_companion: PlayerServiceCompanion) = withContext(Dispatchers.Default) {
        synchronized(service_connected_listeners) {
            service_connection?.also { connection ->
                service_connection_companion!!.disconnect(context, connection)
                service_connection_companion = null
                service_connection = null

                _player?.also {
                    launch(Dispatchers.Main) {
                        it.onDestroy()
                    }
                    _player = null
                }
            }

            service_connecting = false
            connectService(service_companion, onConnected = null)
        }
    }

    val status: PlayerStatus = PlayerStatus()

    fun isRunningAndFocused(): Boolean {
        return controller?.has_focus == true
    }

    fun onSongDownloadRequested(song: Song, always_show_options: Boolean = false, onCompleted: DownloadRequestCallback? = null) {
        onSongDownloadRequested(listOf(song), always_show_options, onCompleted)
    }
    fun onSongDownloadRequested(songs: List<Song>, always_show_options: Boolean = false, callback: DownloadRequestCallback? = null) {
        download_request_songs = songs
        download_request_always_show_options = always_show_options
        download_request_callback = callback
    }
}

fun Dp.notUnspecified(): Dp =
    if (this.isUnspecified) 0.dp else this
