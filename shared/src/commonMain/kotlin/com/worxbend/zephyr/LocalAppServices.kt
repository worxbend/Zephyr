package com.worxbend.zephyr

import androidx.compose.runtime.staticCompositionLocalOf

val LocalAppServices = staticCompositionLocalOf<AppServices> {
    error("AppServices must be provided by the composition root")
}
