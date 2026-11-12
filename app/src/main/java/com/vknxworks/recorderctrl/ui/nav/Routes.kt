package com.vknxworks.recorderctrl.ui.nav

import kotlinx.serialization.Serializable

// Tabs
@Serializable data object RecordRoute
@Serializable data object RecordingsRoute

// Opened from the profile picture: the You page, and Settings from there
@Serializable data object YouRoute
@Serializable data object SettingsRoute
