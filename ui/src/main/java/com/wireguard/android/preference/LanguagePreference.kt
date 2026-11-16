/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.preference

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference

/**
 * In-app language switcher. The locale lives in AppCompat's own storage
 * (autoStoreLocales), so this preference is not persisted in the DataStore.
 */
class LanguagePreference(context: Context, attrs: AttributeSet?) : ListPreference(context, attrs) {
    init {
        isPersistent = false
        value = currentLanguageTag()
        setOnPreferenceChangeListener { _, newValue ->
            val tag = newValue?.toString() ?: LANGUAGE_SYSTEM
            if (tag != currentLanguageTag()) {
                AppCompatDelegate.setApplicationLocales(
                    if (tag == LANGUAGE_SYSTEM) LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(tag)
                )
            }
            true
        }
    }

    private fun currentLanguageTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) LANGUAGE_SYSTEM else locales.toLanguageTags()
    }

    companion object {
        private const val LANGUAGE_SYSTEM = "system"
    }
}
