package com.ooto.faceapidemo.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OotoSuccess<T>(
    @Json(name = "transactionId") val transactionId: String,
    @Json(name = "result") val result: T
)

@JsonClass(generateAdapter = true)
data class AddResult(
    @Json(name = "templateId") val templateId: String?,
    @Json(name = "face") val face: FaceDetails?
)

@JsonClass(generateAdapter = true)
data class AddResponse(
    @Json(name = "transactionId") val transactionId: String,
    @Json(name = "result") val result: AddResult
)

@JsonClass(generateAdapter = true)
data class IdentifyResult(
    @Json(name = "templateId") val templateId: String?,
    @Json(name = "similarity") val similarity: Double?,
    @Json(name = "face") val face: FaceDetails?
)

@JsonClass(generateAdapter = true)
data class IdentifyResponse(
    @Json(name = "transactionId") val transactionId: String,
    @Json(name = "result") val result: IdentifyResult
)

@JsonClass(generateAdapter = true)
data class DeleteRequest(
    @Json(name = "templateId") val templateId: String
)

@JsonClass(generateAdapter = true)
data class DeleteResponse(
    @Json(name = "transactionId") val transactionId: String
)

@JsonClass(generateAdapter = true)
data class OotoErrorPayload(
    @Json(name = "status") val status: String?,
    @Json(name = "code") val code: Int?,
    @Json(name = "info") val info: String?
)

@JsonClass(generateAdapter = true)
data class OotoErrorResponse(
    @Json(name = "transactionId") val transactionId: String?,
    @Json(name = "result") val result: OotoErrorPayload?
)

@JsonClass(generateAdapter = true)
data class FaceDetails(
    @Json(name = "liveness") val liveness: ScoreFlag? = null,
    @Json(name = "deepfake") val deepfake: ScoreFlag? = null,
    @Json(name = "quality") val quality: Quality? = null
)

@JsonClass(generateAdapter = true)
data class ScoreFlag(
    @Json(name = "score") val score: Double? = null,
    @Json(name = "fine") val fine: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class Quality(
    @Json(name = "gender") val gender: String? = null,
    @Json(name = "age") val age: Int? = null
)

fun OotoErrorResponse.toUserMessage(): String {
    val code = result?.code
    val info = result?.info
    return when (code) {
        5 -> "No faces found"
        else -> info ?: "Request failed"
    }
}