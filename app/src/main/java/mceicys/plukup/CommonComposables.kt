package mceicys.plukup

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumTouchTargetEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    innerPadding: Dp = 2.dp,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val visualTransformation = VisualTransformation.None

    val selectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.onBackground,
        backgroundColor = MaterialTheme.colorScheme.secondary
    )

    val colors = TextFieldDefaults.textFieldColors(
        containerColor = MaterialTheme.colorScheme.background,
        placeholderColor = MaterialTheme.colorScheme.onBackground,
        unfocusedIndicatorColor = MaterialTheme.colorScheme.onBackground,
        focusedIndicatorColor = MaterialTheme.colorScheme.onBackground
    )

    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    CompositionLocalProvider(LocalMinimumTouchTargetEnforcement provides false) {
        // Surface surrounds BasicTextField so there's a box border separate from the text-field's indicator line
        Surface(
            onClick = { focusManager.clearFocus(true); focusRequester.requestFocus() },
            // Allows the user to tap the BasicTextField's padding and still get its focus
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.background,
            modifier = modifier.border(2.dp, MaterialTheme.colorScheme.onBackground)
        ) {
            CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .padding(4.dp, 4.dp, 4.dp, 8.dp)
                        .fillMaxWidth(),
                    enabled = enabled,
                    readOnly = readOnly,
                    textStyle = MaterialTheme.typography.bodyLarge.plus(TextStyle(color = MaterialTheme.colorScheme.onBackground)),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    minLines = 1,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                    decorationBox = @Composable { innerTextField ->
                        TextFieldDefaults.TextFieldDecorationBox(
                            value = value,
                            visualTransformation = visualTransformation,
                            innerTextField = innerTextField,
                            placeholder = placeholder,
                            singleLine = singleLine,
                            enabled = enabled,
                            isError = false,
                            interactionSource = interactionSource,
                            shape = TextFieldDefaults.filledShape,
                            colors = colors,
                            trailingIcon = trailingIcon,
                            contentPadding = TextFieldDefaults.textFieldWithoutLabelPadding(
                                innerPadding,
                                innerPadding,
                                innerPadding,
                                innerPadding
                            )
                        )
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CustomButton(modifier: Modifier = Modifier,
                 onClick: () -> Unit = {},
                 onLongClick: (() -> Unit)? = null,
                 borderColor: Color = MaterialTheme.colorScheme.onBackground,
                 contentColor: Color = MaterialTheme.colorScheme.onBackground,
                 backgroundColor: Color = MaterialTheme.colorScheme.background,
                 content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalMinimumTouchTargetEnforcement provides false) {
        Surface(
            shape = RectangleShape,
            color = borderColor,
            modifier = modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
        ) {
            Surface(
                shape = CutCornerShape(size = 8.dp),
                color = backgroundColor,
                contentColor = contentColor,
                modifier = Modifier.padding(2.dp)
            ) {
                Box(
                    content = content,
                    contentAlignment = Alignment.Center
                )
            }
        }
    }
}