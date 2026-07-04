package com.elegen.elegencashbook.core.ui

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.elegen.elegencashbook.R
import com.google.android.material.button.MaterialButton

/** Shared primary-action palette: brand blue when enabled, gray when not. */
fun MaterialButton.setPrimaryEnabled(enabled: Boolean) {
    isEnabled = enabled
    backgroundTintList = ColorStateList.valueOf(
        ContextCompat.getColor(context, if (enabled) R.color.brand_blue else R.color.surface_gray)
    )
    setTextColor(ContextCompat.getColor(context, if (enabled) R.color.white else R.color.text_muted))
}
