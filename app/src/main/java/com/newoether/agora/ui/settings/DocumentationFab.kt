package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R

@Composable
fun DocumentationFab(docPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val baseUrl = "https://newo-ether.github.io/Agora/"

    ExtendedFloatingActionButton(
        onClick = {
            val page = docPath.removeSuffix(".md") + "/"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl$page"))
            context.startActivity(intent)
        },
        icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
        text = { Text(stringResource(R.string.documentation)) },
        shape = RoundedCornerShape(50),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        elevation = FloatingActionButtonDefaults.elevation(4.dp, 4.dp),
        modifier = modifier.navigationBarsPadding()
    )
}
