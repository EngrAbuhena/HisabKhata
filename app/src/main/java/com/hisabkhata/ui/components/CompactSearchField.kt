package com.hisabkhata.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisabkhata.R

/** A normal Material outlined search field with a genuinely compact 48dp container. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    val interactions = remember { MutableInteractionSource() }
    // Unlike OutlinedTextField's default 56dp minimum and 16dp vertical padding,
    // this decoration leaves room for enlarged text inside a 48dp field.
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = 16.sp
    )
    val colors = OutlinedTextFieldDefaults.colors()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = hint },
        singleLine = true,
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactions,
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactions,
                colors = colors,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                placeholder = {
                    FitLedgerText(
                        value = hint,
                        modifier = Modifier.fillMaxWidth(),
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (value.isNotEmpty()) {
                    {
                        IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                } else null
            )
        }
    )
}

/** Exactly one secondary, body-small line; long counts remain available in full. */
@Composable
fun CompactResultCount(value: String, modifier: Modifier = Modifier) {
    FitLedgerText(
        value = value,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Preview(name = "People search - narrow phone", widthDp = 320, heightDp = 180, showBackground = true)
@Preview(name = "People search - normal phone", widthDp = 400, heightDp = 180, showBackground = true)
@Preview(name = "People search - large font", widthDp = 320, heightDp = 200, fontScale = 2f, showBackground = true)
@Composable
private fun CompactPeopleSearchPreview() {
    MaterialTheme {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            CompactSearchField("", {}, stringResource(R.string.search_people))
            Spacer(Modifier.height(8.dp))
            CompactResultCount(pluralStringResource(R.plurals.people_count, 4, 4))
        }
    }
}
