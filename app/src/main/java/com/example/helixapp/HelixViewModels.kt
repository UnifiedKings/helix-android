package com.example.helixapp

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * A screen's ViewModel, created from the Application on first use (scoped like [viewModel]).
 * Pass a [key] when one destination can show different content (e.g. an album id).
 */
@Composable
inline fun <reified VM : ViewModel> helixViewModel(key: String? = null, crossinline create: (Application) -> VM): VM {
    val app = LocalContext.current.applicationContext as Application
    return viewModel(key = key) { create(app) }
}
