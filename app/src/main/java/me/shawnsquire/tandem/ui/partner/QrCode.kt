package me.shawnsquire.tandem.ui.partner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.common.BitMatrix

/** Encodes [text] with a 1-module quiet zone left to the caller (we draw on white with padding). */
internal fun encodeQr(text: String): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))

/** Draws [text] as a QR code on a white square, so it scans in both light and dark themes. */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val matrix = remember(text) { encodeQr(text) }
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        drawRect(Color.White)
        val pad = this.size.minDimension * 0.08f
        val cell = (this.size.minDimension - 2 * pad) / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(Color.Black, Offset(pad + x * cell, pad + y * cell), Size(cell, cell))
                }
            }
        }
    }
}
