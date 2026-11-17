package com.example.spatialphoto

import com.example.spatialphoto.ui.capture.CaptureScreen
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.Stage
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme {
                CaptureScreen()
            }
        }
        Stage(id = "PhotoCapture") {
            PicoTheme { com.example.spatialphoto.ui.capture.FullspaceCaptureScreen() }
        }
    }

