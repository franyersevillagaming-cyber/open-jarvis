
package com.openjarvis.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MCPClient(
    val server: MCPServer
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var isConnected = false

    private var availableTools = emptyList<MCPTool>()

    private fun createRequest(body: String): Request {
        val requestBody = body.toRequestBody(jsonMediaType)

        return Request.Builder()
            .url(server.url)
            .header("Accept", "application/json")
            .post(requestBody)
            .build()
    }

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().apply {
                        put("name", "franyer-ai")
                        put("version", "1.0.0")
                    })
                })
            }.toString()

            val request = createRequest(requestBody)

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    isConnected = false
                    return@withContext false
                }

                val body = response.body?.string()
                    ?: return@withContext false

                val json = JSONObject(body)

                if (json.has("error")) {
                    isConnected = false
                    return@withContext false
                }

                isConnected = json.has("result")
                isConnected
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isConnected = false
            false
        }
    }

    suspend fun listTools(): List<MCPTool> = withContext(Dispatchers.IO) {
        if (!isConnected) {
            return@withContext emptyList()
        }

        try {
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/list")
                put("params", JSONObject())
            }.toString()

            val request = createRequest(requestBody)

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val body = response.body?.string()
                    ?: return@withContext emptyList()

                val json = JSONObject(body)

                if (json.has("error")) {
                    return@withContext emptyList()
                }

                val result = json.optJSONObject("result")
                    ?: return@withContext emptyList()

                val toolsArray = result.optJSONArray("tools")
                    ?: JSONArray()

                availableTools = (0 until toolsArray.length()).map { index ->
                    val tool = toolsArray.getJSONObject(index)

                    MCPTool(
                        name = tool.optString("name", ""),
                        description = tool.optString("description", ""),
                        inputSchema = tool.optJSONObject("inputSchema")
                    )
                }.filter { it.name.isNotBlank() }

                availableTools
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun callTool(
        toolName: String,
        arguments: JSONObject
    ): String = withContext(Dispatchers.IO) {
        if (!isConnected) {
            return@withContext "Error: Not connected"
        }

        try {
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 3)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments)
                })
            }.toString()

            val request = createRequest(requestBody)

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Error: HTTP ${response.code}"
                }

                val body = response.body?.string()
                    ?: return@withContext "Error: Empty response"

                val json = JSONObject(body)

                if (json.has("error")) {
                    val error = json.optJSONObject("error")
                    return@withContext "Error: ${
                        error?.optString("message", "Unknown error")
                            ?: "Unknown error"
                    }"
                }

                val result = json.optJSONObject("result")
                    ?: return@withContext "Tool executed, but no result was returned"

                if (result.optBoolean("isError", false)) {
                    return@withContext "Error: Tool execution failed"
                }

                val content = result.optJSONArray("content")
                    ?: return@withContext "Tool executed"

                val messages = (0 until content.length()).mapNotNull { index ->
                    val item = content.optJSONObject(index)

                    if (item?.optString("type") == "text") {
                        item.optString("text").takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }

                messages.joinToString("\n").ifBlank {
                    "Tool executed"
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message ?: "Unknown error"}"
        }
    }

    fun disconnect() {
        isConnected = false
        availableTools = emptyList()
    }

    fun isConnected(): Boolean = isConnected

    fun getToolCount(): Int = availableTools.size

    companion object {
        const val HTTP = "http"
        const val SSE = "sse"
    }
}

data class MCPServer(
    val id: String,
    val name: String,
    val url: String,
    val apiKey: String? = null,
    val enabled: Boolean = true
)

data class MCPTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject? = null
)
