package com.ooto.faceapidemo.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ooto.faceapidemo.api.OotoClient
import com.ooto.faceapidemo.model.*
import com.ooto.faceapidemo.util.toJpegPart
import com.squareup.moshi.adapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response

data class UiState(
    val loading: Boolean = false,
    val status: String = "Status output will appear here…",
    val bitmap: Bitmap? = null,
    val currentTemplateId: String? = null
)

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun setStatus(text: String) {
        _state.value = _state.value.copy(status = text)
    }

    fun onPhotoCaptured(bmp: Bitmap?) {
        if (bmp == null) {
            _state.value = _state.value.copy(status = "No photo")
            return
        }
        _state.value = _state.value.copy(bitmap = bmp, status = "Photo captured")
    }

    fun enroll() {
        val bmp = _state.value.bitmap ?: run {
            setStatus("Take a photo first")
            return
        }
        viewModelScope.launch {
            setLoading(true, "Enroll: uploading…")
            val part = withContext(Dispatchers.Default) { bmp.toJpegPart() }
            val response = runCatching {
                OotoClient.api.add(
                    photo = part,
                    templateId = null,
                    checkLiveness = false,
                    checkDeepfake = false
                )
            }.getOrElse { e ->
                setLoading(false, "Network error: ${e.message}")
                return@launch
            }
            handleAddResponse(response)
        }
    }

    fun identify() {
        val bmp = _state.value.bitmap ?: run {
            setStatus("Take a photo first")
            return
        }
        viewModelScope.launch {
            setLoading(true, "Identify: uploading…")
            val part = withContext(Dispatchers.Default) { bmp.toJpegPart() }
            val response = runCatching {
                OotoClient.api.identify(
                    photo = part,
                    checkLiveness = false,
                    checkDeepfake = false
                )
            }.getOrElse { e ->
                setLoading(false, "Network error: ${e.message}")
                return@launch
            }
            handleIdentifyResponse(response)
        }
    }

    fun deleteTemplate() {
        val templateId = _state.value.currentTemplateId ?: run {
            setStatus("Nothing to delete")
            return
        }
        viewModelScope.launch {
            setLoading(true, "Delete: sending…")
            val response = runCatching {
                OotoClient.api.delete(DeleteRequest(templateId))
            }.getOrElse { e ->
                setLoading(false, "Network error: ${e.message}")
                return@launch
            }

            if (response.isSuccessful && response.body() != null) {
                setLoading(false, "Deleted: transactionId=${response.body()!!.transactionId}")
                _state.value = _state.value.copy(currentTemplateId = null)
            } else {
                val msg = parseApiErrorMessage(response.errorBody())
                setLoading(false, "Delete failed: $msg")
            }
        }
    }

    // ---- Internals ----

    private fun handleAddResponse(response: Response<AddResponse>) {
        if (response.isSuccessful && response.body() != null) {
            val r = response.body()!!
            val tid = r.result.enroll?.templateId
            val text = buildString {
                appendLine("Enroll OK")
                appendLine("transactionId=${r.transactionId}")
                appendLine("templateId=$tid")
            }
            _state.value = _state.value.copy(
                status = text,
                currentTemplateId = tid ?: _state.value.currentTemplateId
            )
        } else {
            val msg = parseApiErrorMessage(response.errorBody())
            _state.value = _state.value.copy(status = "Enroll failed: $msg")
        }
        _state.value = _state.value.copy(loading = false)
    }

    private fun handleIdentifyResponse(response: Response<IdentifyResponse>) {
        if (response.isSuccessful && response.body() != null) {
            val r = response.body()!!
            val tid = r.result.search?.templateId
            val sim = r.result.search?.similarity
            val text = buildString {
                appendLine("Identify OK")
                appendLine("transactionId=${r.transactionId}")
                appendLine("templateId=$tid")
                appendLine("similarity=$sim")
            }
            _state.value = _state.value.copy(
                status = text,
                currentTemplateId = tid ?: _state.value.currentTemplateId
            )
        } else {
            val msg = parseApiErrorMessage(response.errorBody())
            _state.value = _state.value.copy(status = "Identify failed: $msg")
        }
        _state.value = _state.value.copy(loading = false)
    }

    private fun setLoading(isLoading: Boolean, statusText: String? = null) {
        _state.value = _state.value.copy(
            loading = isLoading,
            status = statusText ?: _state.value.status
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun parseApiErrorMessage(errorBody: ResponseBody?): String {
        if (errorBody == null) return "Unknown error"
        return try {
            val json = errorBody.string()
            val adapter = OotoClient.moshi().adapter<OotoErrorResponse>()
            val parsed = adapter.fromJson(json)
            parsed?.toUserMessage() ?: "Request failed"
        } catch (_: Exception) {
            "Request failed"
        }
    }
}