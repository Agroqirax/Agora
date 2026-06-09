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
    private const val TARGET_LONG_EDGE = 1536

    fun renderAsImages(context: Context, uri: Uri, pages: Set<Int>? = null): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()

        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        val renderer = PdfRenderer(ParcelFileDescriptor(fd))
        val paths = mutableListOf<String>()

        val totalPages = renderer.pageCount
        val selectedPages = pages?.filter { it in 0 until totalPages }?.toSet()
            ?: (0 until minOf(totalPages, MAX_PAGES)).toSet()
        for (i in selectedPages.sorted()) {
            val page = renderer.openPage(i)
            val scale = TARGET_LONG_EDGE.toFloat() / maxOf(page.width, page.height)
            val scaledWidth = (page.width * scale).toInt().coerceAtLeast(1)
            val scaledHeight = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(
                scaledWidth, scaledHeight,
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

    fun renderAllPages(context: Context, uri: Uri, maxPages: Int = 200, onProgress: ((current: Int, total: Int) -> Unit)? = null): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()

        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        val renderer = PdfRenderer(ParcelFileDescriptor(fd))
        val paths = mutableListOf<String>()
        val effectiveTotal = minOf(renderer.pageCount, maxPages)

        for (i in 0 until effectiveTotal) {
            val page = renderer.openPage(i)
            val scale = TARGET_LONG_EDGE.toFloat() / maxOf(page.width, page.height)
            val scaledWidth = (page.width * scale).toInt().coerceAtLeast(1)
            val scaledHeight = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val file = File(context.filesDir, "pdf_preview_${UUID.randomUUID()}_$i.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
            paths.add(file.absolutePath)
            onProgress?.invoke(i + 1, effectiveTotal)
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
