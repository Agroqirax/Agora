package com.newoether.agora.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID

object PdfPageRenderer {
    private const val MAX_PAGES = 5
    private const val SCALE_FACTOR = 0.5f

    fun renderAsImages(context: Context, uri: Uri): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()

        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        val renderer = PdfRenderer(ParcelFileDescriptor(fd))
        val paths = mutableListOf<String>()

        val pageCount = minOf(renderer.pageCount, MAX_PAGES)
        for (i in 0 until pageCount) {
            val page = renderer.openPage(i)
            val scaledWidth = (page.width * SCALE_FACTOR).toInt()
            val scaledHeight = (page.height * SCALE_FACTOR).toInt()
            val bitmap = Bitmap.createBitmap(
                scaledWidth.coerceAtLeast(1), scaledHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val file = File(context.filesDir, "pdf_${UUID.randomUUID()}_$i.jpg")
            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
            }
            bitmap.recycle()
            paths.add(file.absolutePath)
        }
        renderer.close()
        return paths
    }

    fun getPageCount(context: Context, uri: Uri): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return 0
        return try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return 0
            val renderer = PdfRenderer(ParcelFileDescriptor(fd))
            val count = renderer.pageCount
            renderer.close()
            count
        } catch (_: Exception) { 0 }
    }
}
