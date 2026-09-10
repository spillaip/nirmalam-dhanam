package com.nirmalamgroup.nirmalamdhanam.data.local

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.InvestmentPerformanceMetric
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Generates a simple PDF report for investment performance.
 */
class PdfReportExporter(private val context: Context) {
    
    fun exportPerformanceReport(
        destination: Uri,
        @Suppress("UNUSED_PARAMETER") currencyCode: String,
        metrics: List<InvestmentPerformanceMetric>,
    ): Boolean {
        val document = PdfDocument()
        val pageWidth = 842 // A4 Landscape roughly
        val pageHeight = 595
        
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas
        
        val paint = Paint()
        paint.textSize = 10f
        paint.color = Color.BLACK
        
        var y = 40f
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas.drawText("Nirmalam Dhanam - Nivesha Performance Report", 40f, y, paint)
        y += 30f
        
        // Table Header
        paint.textSize = 10f
        paint.isFakeBoldText = true
        val headers = listOf("Month", "Cost", "Value", "Contrib.", "Apprec.", "Reval.", "Gain%", "Month%", "Port%", "XIRR%")
        val colWidths = listOf(80f, 80f, 80f, 80f, 80f, 80f, 60f, 60f, 60f, 60f)
        
        fun drawHeader(c: Canvas, currentY: Float) {
            var x = 40f
            headers.forEachIndexed { i, header ->
                c.drawText(header, x, currentY, paint)
                x += colWidths[i]
            }
        }

        drawHeader(canvas, y)
        y += 15f
        canvas.drawLine(40f, y, 40f + colWidths.sum(), y, paint)
        y += 15f
        paint.isFakeBoldText = false
        
        val monthFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
        
        metrics.forEach { metric ->
            if (y > (pageHeight - 40)) {
                document.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = document.startPage(pageInfo)
                canvas = page.canvas
                y = 40f
                paint.isFakeBoldText = true
                drawHeader(canvas, y)
                y += 15f
                canvas.drawLine(40f, y, 40f + colWidths.sum(), y, paint)
                y += 15f
                paint.isFakeBoldText = false
            }
            
            var x = 40f
            val values = listOf(
                metric.month.format(monthFormatter),
                formatPaise(metric.cost),
                formatPaise(metric.value),
                formatPaise(metric.contribution),
                formatPaise(metric.appreciation),
                formatPaise(metric.revaluation),
                formatPercent(metric.totalGainPercent),
                formatPercent(metric.monthlyReturnPercent),
                formatPercent(metric.portfolioReturnPercent),
                formatPercent(metric.xirrPercent)
            )
            
            values.forEachIndexed { i, value ->
                canvas.drawText(value, x, y, paint)
                x += colWidths[i]
            }
            y += 20f
        }
        
        document.finishPage(page)
        
        return try {
            context.contentResolver.openOutputStream(destination)?.use { output ->
                document.writeTo(output)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            document.close()
        }
    }
    
    private fun formatPaise(paise: Long): String {
        return "%.2f".format(paise / 100.0)
    }
    
    private fun formatPercent(percent: Double?): String {
        return percent?.let { "%.1f%%".format(it) } ?: "-"
    }
}
