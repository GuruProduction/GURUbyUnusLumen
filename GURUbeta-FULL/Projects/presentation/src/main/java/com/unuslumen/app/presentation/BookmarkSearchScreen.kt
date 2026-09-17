package com.unuslumen.app.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.unuslumen.app.ui.ItemView
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.snackbar.LocalisedSnackbarHost
import com.unuslumen.app.ui.snackbar.showSnackbar
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun BookmarkSearchScreen(
    navController: NavHostController,
    viewModel: BookmarksViewModel = koinViewModel()
) {
    val state = viewModel.uiState
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { LocalisedSnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            var query by rememberSaveable {
                mutableStateOf("")
            }
            LaunchedEffect(query) { viewModel.onEvent(BookmarkEvent.SearchBookmarks(query)) }
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(true) { focusRequester.requestFocus() }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_bookmarks)) },
                shape = RoundedCornerShape(15.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .focusRequester(focusRequester)
            )
            if (state.bookmarksView == ItemView.LIST) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    items(state.searchBookmarks, key = { it.id }) { bookmark ->
                        BookmarkItem(
                            bookmark = bookmark,
                            onClick = {
                                navController.navigate(
                                    Screen.BookmarkDetailScreen(
                                        bookmarkId = bookmark.id
                                    )
                                )
                            },
                            onInvalidUrl = {
                                scope.launch {
                                    snackbarHostState.showSnackbar(R.string.invalid_url)
                                }
                            }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    items(state.searchBookmarks) { bookmark ->
                        key(bookmark.id) {
                            BookmarkItem(
                                bookmark = bookmark,
                                onClick = {
                                    navController.navigate(
                                        Screen.BookmarkDetailScreen(
                                            bookmarkId = bookmark.id
                                        )
                                    )
                                },
                                onInvalidUrl = {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(R.string.invalid_url)
                                    }
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
}