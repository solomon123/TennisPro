package com.tennispro.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.wear.compose.material.MaterialTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val link = remember { WatchLink(this) }
            val haptics = remember { Haptics(this) }

            // Wear's own MaterialTheme, not the phone's Material 3 one: the watch
            // palette is always dark and the type scale is built for a small round
            // display at arm's length.
            MaterialTheme {
                WatchApp(link = link, haptics = haptics)
            }
        }
    }
}
