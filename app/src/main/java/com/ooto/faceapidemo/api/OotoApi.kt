package com.ooto.faceapidemo.api

import com.ooto.faceapidemo.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface OotoApi {
    @Multipart
    @POST("add")
    suspend fun add(
        @Part photo: MultipartBody.Part,
        @Part("templateId") templateId: RequestBody? = null,
        @Query("check_liveness") checkLiveness: Boolean = false,
        @Query("check_deepfake") checkDeepfake: Boolean = false
    ): Response<AddResponse>

    @Multipart
    @POST("identify")
    suspend fun identify(
        @Part photo: MultipartBody.Part,
        @Query("check_liveness") checkLiveness: Boolean = false,
        @Query("check_deepfake") checkDeepfake: Boolean = false
    ): Response<IdentifyResponse>

    @POST("delete")
    suspend fun delete(
        @Body body: DeleteRequest
    ): Response<DeleteResponse>
}