package com.example.helixapp

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/** A screen's ViewModel, created from the Application on first use (scoped like [viewModel]). */
@Composable
inline fun <reified VM : ViewModel> helixViewModel(crossinline create: (Application) -> VM): VM {
    val app = LocalContext.current.applicationContext as Application
    return viewModel { create(app) }
}
