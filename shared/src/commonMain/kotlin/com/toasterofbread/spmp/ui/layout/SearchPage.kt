package com.toasterofbread.spmp.ui.layout

import LocalPlayerState
import SpMp
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import com.toasterofbread.spmp.api.*
import com.toasterofbread.spmp.model.mediaitem.enums.MediaItemType
import com.toasterofbread.spmp.model.mediaitem.enums.PlaylistType
import com.toasterofbread.spmp.model.mediaitem.enums.getReadable
import com.toasterofbread.spmp.platform.composable.BackHandler
import com.toasterofbread.spmp.platform.getDefaultHorizontalPadding
import com.toasterofbread.spmp.resources.getString
import com.toasterofbread.spmp.ui.component.ErrorInfoDisplay
import com.toasterofbread.spmp.ui.component.mediaitemlayout.MediaItemLayout
import com.toasterofbread.spmp.ui.component.MultiselectAndMusicTopBar
import com.toasterofbread.spmp.ui.component.multiselect.MediaItemMultiSelectContext
import com.toasterofbread.spmp.ui.theme.Theme
import com.toasterofbread.utils.*
import com.toasterofbread.utils.composable.ShapedIconButton
import com.toasterofbread.utils.composable.SubtleLoadingIndicator
import com.toasterofbread.utils.composable.rememberKeyboardOpen
import com.toasterofbread.spmp.ui.layout.nowplaying.LocalNowPlayingExpansion
import com.toasterofbread.spmp.ui.layout.mainpage.MainPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren

val SEARCH_FIELD_FONT_SIZE: TextUnit = 18.sp
private val SEARCH_BAR_HEIGHT = 45.dp
private val SEARCH_BAR_V_PADDING = 15.dp

class SearchPage: MainPage() {
    private val coroutine_scope = CoroutineScope(Job())
    private val search_lock = Object()
    
    private var clearFocus: (() -> Unit)? = null
    private var multiselect_context: MediaItemMultiSelectContext? = null

    private var search_in_progress: Boolean by mutableStateOf(false)
    private var current_results: SearchResults? by mutableStateOf(null)
    private var current_query: String by mutableStateOf("")
    private var current_filter: SearchType? by mutableStateOf(null)
    private var error: Throwable? by mutableStateOf(null)

    override fun onOpened() {
        coroutine_scope.coroutineContext.cancelChildren()
        search_in_progress = false
        current_results = null
        current_query = ""
        current_filter = null
        error = null
    }
    
    private fun setFilter(filter: SearchType?) {
        if (filter == current_filter) {
            return
        }

        current_filter = filter
        if (current_results != null || search_in_progress) {
            performSearch()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
    @Composable
    override fun Page(
        multiselect_context: MediaItemMultiSelectContext, 
        modifier: Modifier, 
        content_padding: PaddingValues, 
        close: () -> Unit
    ) {
        val player = LocalPlayerState.current
        val keyboard_controller = LocalSoftwareKeyboardController.current
        val focus_manager = LocalFocusManager.current
        val focus_state = remember { mutableStateOf(false) }

        DisposableEffect(focus_manager, keyboard_controller) {
            clearFocus = {
                focus_manager.clearFocus()
                keyboard_controller?.hide()
            }
            this@SearchPage.multiselect_context = multiselect_context

            onDispose {
                clearFocus = null
                this@SearchPage.multiselect_context = null
            }
        }

        val keyboard_open by rememberKeyboardOpen()
        LaunchedEffect(keyboard_open) {
            if (!keyboard_open) {
                clearFocus?.invoke()
            }
        }

        Box(modifier) {
            Column(Modifier.fillMaxSize()) {
                val padding = content_padding.copy(
                    bottom = content_padding.calculateBottomPadding() + SEARCH_BAR_HEIGHT + (SEARCH_BAR_V_PADDING * 2)
                )

                Crossfade(
                    error ?: current_results
                ) { results ->
                    if (results is SearchResults) {
                        Results(
                            results,
                            padding,
                            multiselect_context
                        )
                    }
                    else if (results is Throwable) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentAlignment = Alignment.Center
                        ) {
                            ErrorInfoDisplay(results, Modifier.fillMaxWidth())
                        }
                    }
                    else if (search_in_progress) {
                        Box(
                            Modifier.fillMaxSize().padding(padding),
                            contentAlignment = Alignment.Center
                        ) {
                            SubtleLoadingIndicator(getColour = { Theme.on_background }, message = getString("search_results_loading"))
                        }
                    }
                }
            }

            SearchBar(
                focus_state,
                player.nowPlayingTopOffset(Modifier.align(Alignment.BottomCenter)),
                close
            )
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
    @Composable
    fun FilterBar(modifier: Modifier = Modifier) {
        val chip_colours = FilterChipDefaults.filterChipColors(
            containerColor = Theme.background,
            labelColor = Theme.on_background,
            selectedContainerColor = Theme.accent,
            selectedLabelColor = Theme.on_accent
        )

        Row(
            modifier
                .horizontalScroll(rememberScrollState())
                .height(SEARCH_BAR_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (type in listOf(null) + SearchType.values()) {
                FilterChip(
                    selected = current_filter == type,
                    onClick = {
                        setFilter(type)
                    },
                    label = {
                        Text(when (type) {
                            null -> getString("search_filter_all")
                            SearchType.VIDEO -> getString("search_filter_videos")
                            SearchType.SONG -> MediaItemType.SONG.getReadable(true)
                            SearchType.ARTIST -> MediaItemType.ARTIST.getReadable(true)
                            SearchType.PLAYLIST -> PlaylistType.PLAYLIST.getReadable(true)
                            SearchType.ALBUM -> PlaylistType.ALBUM.getReadable(true)
                        })
                    },
                    colors = chip_colours
                )
            }
        }
    }

    fun performSearch() {
        performSearch(current_filter?.let { SearchFilter(it, it.getDefaultParams()) })
    }

    fun performSearch(filter: SearchFilter?) {
        clearFocus?.invoke()
        
        synchronized(search_lock) {
            search_in_progress = true
            
            val query = current_query
            current_results = null
            current_filter = filter?.type
            multiselect_context?.setActive(false)
            
            coroutine_scope.launchSingle {
                val result = searchYoutubeMusic(query, filter?.params)
                result.fold(
                    { results ->
                        for (result in results.categories) {
                            if (result.second != null) {
                                result.first.view_more = MediaItemLayout.ViewMore(
                                    action = {
                                        performSearch(result.second)
                                    }
                                )
                            }
                        }

                        synchronized(search_lock) {
                            current_results = results
                            search_in_progress = false
                        }
                    },
                    {
                        error = it
                        synchronized(search_lock) {
                            search_in_progress = false
                        }
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SearchBar(
        focus_state: MutableState<Boolean>,
        modifier: Modifier = Modifier,
        close: () -> Unit
    ) {
        val expansion = LocalNowPlayingExpansion.current
        var query_text by remember { mutableStateOf("") }
        val focus_requester = remember { FocusRequester() }
    
        LaunchedEffect(Unit) {
            if (expansion.getPage() == 0 && current_results == null && !search_in_progress) {
                focus_requester.requestFocus()
            }
        }
    
        Row(
            modifier
                .fillMaxWidth()
                .padding(vertical = SEARCH_BAR_V_PADDING, horizontal = SpMp.context.getDefaultHorizontalPadding())
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            ShapedIconButton(
                close,
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
                colours = IconButtonDefaults.iconButtonColors(
                    containerColor = Theme.accent,
                    contentColor = Theme.on_accent
                )
            ) {
                Icon(Icons.Filled.Close, null)
            }

            BasicTextField(
                value = current_query,
                onValueChange = { current_query = it },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    fontSize = SEARCH_FIELD_FONT_SIZE,
                    color = Theme.on_accent
                ),
                modifier = Modifier
                    .height(SEARCH_BAR_HEIGHT)
                    .weight(1f)
                    .focusRequester(focus_requester)
                    .onFocusChanged {
                        focus_state.value = it.isFocused
                    },
                decorationBox = { innerTextField ->
                    Row(
                        Modifier
                            .background(
                                Theme.accent,
                                CircleShape
                            )
                            .padding(horizontal = 10.dp)
                            .fillMaxSize(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        // Search field
                        Box(Modifier.fillMaxWidth(0.9f), contentAlignment = Alignment.CenterStart) {

                            // Query hint
                            if (current_query.isEmpty()) {
                                Text(getString("search_entry_field_hint"), fontSize = SEARCH_FIELD_FONT_SIZE, color = Theme.on_accent)
                            }

                            // Text input
                            innerTextField()
                        }

                        // Clear field button
                        IconButton(onClick = { current_query = "" }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Clear, null, Modifier, Theme.on_accent)
                        }

                        // Search button / search indicator
                        Crossfade(search_in_progress) { in_progress ->
                            if (!in_progress) {
                                IconButton(onClick = {
                                    if (!search_in_progress) {
                                        performSearch()
                                    }
                                }) {
                                    Icon(Icons.Filled.Search, null)
                                }
                            }
                            else {
                                CircularProgressIndicator(Modifier.size(30.dp))
                            }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (!search_in_progress) {
                            performSearch()
                        }
                    }
                )
            )

            ShapedIconButton(
                { performSearch() },
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
                colours = IconButtonDefaults.iconButtonColors(
                    containerColor = Theme.accent,
                    contentColor = Theme.on_accent
                )
            ) {
                Icon(Icons.Filled.Search, null)
            }
        }
    }
}

@Composable
private fun Results(results: SearchResults, padding: PaddingValues, multiselect_context: MediaItemMultiSelectContext) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(25.dp)
    ) {
        if (results.suggested_correction != null) {
            item {
                // TODO
                Text(results.suggested_correction)
            }
        }

        for (category in results.categories.withIndex()) {
            val layout = category.value.first
            item {
                (layout.type ?: MediaItemLayout.Type.LIST).Layout(layout, multiselect_context = multiselect_context)
            }
        }
    }
}
