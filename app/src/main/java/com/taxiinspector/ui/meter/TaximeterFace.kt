package com.taxiinspector.ui.meter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxiinspector.ui.theme.CabYellow
import com.taxiinspector.ui.theme.Housing
import com.taxiinspector.ui.theme.HousingEdge
import com.taxiinspector.ui.theme.Lcd
import com.taxiinspector.ui.theme.LcdText
import com.taxiinspector.ui.theme.MeterGreen

/**
 * The vintage meter face. It is a pure drawing component: it receives formatted values and
 * their content descriptions, and neither calculates a fare nor reads state for itself.
 */
@Composable
fun TaximeterFace(
    phaseLabel: String,
    total: String,
    totalDescription: String,
    distanceLabel: String,
    distance: String,
    distanceDescription: String,
    waitTimeLabel: String,
    waitTime: String,
    waitTimeDescription: String,
    isLampLit: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Housing)
            .border(3.dp, CabYellow, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isLampLit) MeterGreen else HousingEdge),
            )
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.labelLarge,
                color = CabYellow,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Lcd)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = total,
                style = MaterialTheme.typography.displayLarge,
                color = LcdText,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = totalDescription },
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MeterReadout(
                    label = distanceLabel,
                    value = distance,
                    description = distanceDescription,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(38.dp)
                        .background(LcdText.copy(alpha = 0.35f)),
                )
                MeterReadout(
                    label = waitTimeLabel,
                    value = waitTime,
                    description = waitTimeDescription,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MeterReadout(
    label: String,
    value: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Screen readers announce the merged description above instead of the two labels.
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LcdText.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            color = LcdText,
        )
    }
}
