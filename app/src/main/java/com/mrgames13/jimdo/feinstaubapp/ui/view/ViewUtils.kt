/*
 * Copyright © Marc Auberer 2017 - 2020. All rights reserved
 */

package com.mrgames13.jimdo.feinstaubapp.ui.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Intent
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.app.ActivityCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mrgames13.jimdo.feinstaubapp.R
import kotlin.math.max

fun openActivityWithRevealAnimation(activity: Activity, fab: FloatingActionButton, revealSheet: View, intent: Intent, requestCode: Int) {
    val fabLoc = IntArray(2)
    fab.getLocationOnScreen(fabLoc)
    val screenMetrics = DisplayMetrics()
    activity.windowManager.defaultDisplay.getMetrics(screenMetrics)
    ViewAnimationUtils.createCircularReveal(revealSheet, fabLoc[0] + fab.width / 2, fabLoc[1] + fab.width / 2, fab.width/ 2f, screenMetrics.heightPixels.toFloat()).run {
        duration = 400
        interpolator = AccelerateDecelerateInterpolator()
        doOnEnd {
            ActivityCompat.startActivityForResult(activity, intent, requestCode, null)
            activity.overridePendingTransition(R.anim.activity_transition_slide_up, R.anim.activity_transition_fade_out)
        }
        start()
    }
    revealSheet.visibility = View.VISIBLE
}

fun closeActivityWithRevealAnimation(activity: Activity, fab: FloatingActionButton, revealSheet: View) {
    val fabLoc = IntArray(2)
    fab.getLocationOnScreen(fabLoc)
    val screenMetrics = DisplayMetrics()
    activity.windowManager.defaultDisplay.getMetrics(screenMetrics)
    ViewAnimationUtils.createCircularReveal(revealSheet, fabLoc[0] + fab.width / 2, fabLoc[1] + fab.width / 2, screenMetrics.heightPixels.toFloat(), fab.width/ 2f).run {
        duration = 400
        interpolator = AccelerateDecelerateInterpolator()
        doOnEnd { revealSheet.visibility = View.GONE }
        start()
    }
}

fun enterReveal(v: View) {
    val finalRadius = max(v.width, v.height) + 40f
    ViewAnimationUtils.createCircularReveal(v, 275, v.measuredHeight, 0f, finalRadius).run {
        duration = 350
        start()
    }
    v.visibility = View.VISIBLE
}

fun exitReveal(v: View) {
    val initialRadius = max(v.width, v.height) + 40f
    ViewAnimationUtils.createCircularReveal(v, 275, v.measuredHeight, initialRadius, 0f).run {
        duration = 350
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                v.visibility = View.INVISIBLE
            }
        })
        start()
    }
}