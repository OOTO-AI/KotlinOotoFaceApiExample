package com.ooto.faceapidemo.util

import android.graphics.Bitmap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody

fun Bitmap.toJpegPart(
    partName: String = "photo",
    fileName: String = "photo.jpg",
    quality: Int = 100
): MultipartBody.Part {
    val baos = java.io.ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.JPEG, quality, baos)
    val bytes = baos.toByteArray()
    val mediaType = "image/jpeg".toMediaType()
    val body: RequestBody = RequestBody.create(mediaType, bytes)
    return MultipartBody.Part.createFormData(partName, fileName, body)
}

fun String.toTextRequestBody(): RequestBody =
    RequestBody.create("text/plain".toMediaType(), this)