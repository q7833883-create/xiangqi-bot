package com.xiangqi.autoassist

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import java.nio.ByteBuffer

/** 封装 MediaProjection 虚拟屏 + ImageReader，按需取最新帧为 Bitmap */
class ScreenCapturer(
    projection: MediaProjection,
    width: Int,
    height: Int
) {
    private val reader: ImageReader =
        ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
    private val display: VirtualDisplay

    init {
        val dpi = Resources.getSystem().displayMetrics.densityDpi
        display = projection.createVirtualDisplay(
            "xq-capture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
    }

    /** 取最新一帧；失败返回 null */
    @SuppressLint("WrongCall")
    fun latestBitmap(): Bitmap? {
        val img: Image = try {
            reader.acquireLatestImage() ?: return null
        } catch (e: IllegalStateException) {
            return null
        }
        return try {
            imageToBitmap(img)
        } catch (e: Exception) {
            null
        } finally {
            img.close()
        }
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val w = img.width
        val h = img.height
        val plane = img.planes[0]
        val buf: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pxStride = plane.pixelStride
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (pxStride == 4 && rowStride == w * 4) {
            bmp.copyPixelsFromBuffer(buf)
        } else {
            val row = ByteArray(w * 4)
            val data = ByteArray(h * w * 4)
            var out = 0
            for (y in 0 until h) {
                buf.position(y * rowStride)
                buf.get(row, 0, w * 4)
                System.arraycopy(row, 0, data, out, w * 4)
                out += w * 4
            }
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(data))
        }
        return bmp
    }

    fun close() {
        try { display.release() } catch (e: Exception) {}
        try { reader.close() } catch (e: Exception) {}
    }

    companion object {
        /** 当前主屏真实分辨率 */
        fun screenMetrics(ctx: android.content.Context): DisplayMetrics {
            val dm = DisplayMetrics()
            val dmgr = ctx.getSystemService(DisplayManager::class.java)
            val disp: Display? = dmgr.getDisplay(Display.DEFAULT_DISPLAY)
            if (disp != null) {
                @Suppress("DEPRECATION")
                disp.getRealMetrics(dm)
            }
            if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
                dm.widthPixels = 1080
                dm.heightPixels = 2400
            }
            return dm
        }
    }
}
