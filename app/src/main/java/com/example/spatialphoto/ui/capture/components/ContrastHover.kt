package com.example.spatialphoto.ui.capture.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.hover.SpatialHoverEffectGroup
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.hover.spatialHoverEffectGroup

/** The real native button triggers a system hover group; the initially invisible
 * decoration only responds. It must not rely on hit-testing its transparent pixels.
 * Native click/focus/press/semantics remain on the button. No application hover or
 * gaze events are observed, and the effect contains no scale or movement.
 */
@Composable internal fun ContrastHover(enabled:Boolean, content:@Composable (Modifier)->Unit) {
    val group=remember { SpatialHoverEffectGroup.obtain() }
    val trigger=Modifier.spatialHoverEffectGroup(group.behavior(SpatialHoverEffectGroup.Behavior.Trigger))
    Box {
        content(trigger)
        if(enabled)Box(Modifier.matchParentSize()
            .clip(CircleShape)
            .spatialHoverEffectGroup(group.behavior(SpatialHoverEffectGroup.Behavior.Responder))
            .spatialHoverEffect { alpha(if(it.isActive)1f else 0f) }
            .background(PicoTheme.colorScheme.lightenHover.copy(alpha=.40f))
            .clearAndSetSemantics {})
    }
}
