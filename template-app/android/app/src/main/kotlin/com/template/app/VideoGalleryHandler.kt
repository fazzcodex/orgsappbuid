package com.template.app

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

object VideoGalleryHandler {

    fun getVideos(context: Context): JSONArray {
        val arr = JSONArray()

        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_ADDED
            )

            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                var count = 0
                while (it.moveToNext() && count < 100) {
                    val obj = JSONObject()
                    val id = it.getLong(idCol)

                    obj.put("id", id.toString())
                    obj.put("name", it.getString(nameCol) ?: "unknown.mp4")
                    obj.put("path", it.getString(dataCol) ?: "")
                    obj.put("size", it.getLong(sizeCol))
                    obj.put("duration", it.getLong(durationCol))
                    obj.put("createdAt", it.getLong(dateCol) * 1000)

                    // Thumbnail URL (content:// uri)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    obj.put("thumbnailUrl", contentUri.toString())

                    arr.put(obj)
                    count++
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return arr
    }
}
