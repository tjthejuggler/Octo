package com.example.octo.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.octo.R

/**
 * THE canonical top bar — the single implementation used by every top-level
 * tab (Body / Stats / Library / Settings). 32dp rounded mascot + bold brand /
 * screen title, top-padded 8dp, zero horizontal padding of its own (the screen
 * column owns that). Do NOT hand-roll headers in screens; extend this if a tab
 * needs trailing actions (see [actions]).
 *
 * Body passes its date-nav controls into [actions]; the leading cluster
 * (icon + title) is byte-identical everywhere.
 */
@Composable
fun OctoHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.octo_mascot),
            contentDescription = null, // decorative
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** Convenience overload for the icon+title+subtitle variant used on Stats/Library/Settings. */
@Composable
fun OctoHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.octo_mascot),
                contentDescription = null, // decorative
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 1.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 40.dp),
        )
    }
}

