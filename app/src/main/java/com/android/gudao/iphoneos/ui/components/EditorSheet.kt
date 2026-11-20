package com.android.gudao.iphoneos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.ui.GlasmioActionSheetContent
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioListRow

/**
 * 一次文本编辑的完整描述。放在一个 data class 里，页面只需要一个可空状态，
 * 不用为每个字段各写一个 bool / 一个 sheet。
 */
data class Editor(
    val title: String,
    val hint: String,
    val initial: String,
    val secret: Boolean,
    val placeholder: String,
    /** 返回 false 表示校验没过（错误提示由调用方负责），弹层保持打开。 */
    val onConfirm: (String) -> Boolean,
)

/**
 * 文本编辑用的底部弹层。
 *
 * 用 sheet 而不是 Dialog：Dialog 是独立窗口，拿不到主窗口的 backdrop，玻璃材质在它上面会变成
 * 一块没有采样来源的半透明板；sheet 在同一棵组合树里，glass 正常。
 */
@Composable
fun EditorSheet(editor: Editor?, onDismiss: () -> Unit, accent: Color) {
    var text by remember(editor) { mutableStateOf(editor?.initial.orEmpty()) }

    GlasmioBottomSheet(visible = editor != null, fitContent = true, onDismissRequest = onDismiss) {
        val current = editor ?: return@GlasmioBottomSheet
        GlasmioActionSheetContent(current.title, message = current.hint) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(LocalContentColor.current.copy(alpha = 0.07f))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        current.placeholder,
                        style = IosTypography.subheadline,
                        color = LocalContentColor.current.copy(alpha = 0.3f),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = IosTypography.subheadline.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(accent),
                    singleLine = true,
                    visualTransformation = if (current.secret) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier.fillMaxWidth().testTag("field_editor"),
                )
            }
            Spacer(Modifier.height(6.dp))
            GlasmioListRow(
                "保存",
                onClick = {
                    if (current.onConfirm(text.trim())) onDismiss()
                },
            )
            GlasmioListRow("取消", onClick = onDismiss)
        }
    }
}
