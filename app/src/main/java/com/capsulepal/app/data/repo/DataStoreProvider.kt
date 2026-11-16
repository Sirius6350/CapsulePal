package com.capsulepal.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.capsuleDataStore: DataStore<Preferences> by preferencesDataStore(name = "capsule_prefs")
