package com.billtt.riddle

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import java.time.Duration
import java.util.Base64

/** Anthropic backend: official Java SDK, Claude reads the handwritten page via vision. */
class AnthropicOracle(private val apiKey: String, private val model: String) : Oracle {

    override fun ask(pagePng: ByteArray): String {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(120))
            .build()
        try {
            val image = ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                        .data(Base64.getEncoder().encodeToString(pagePng))
                        .build()
                )
                .build()

            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(300L)
                .system(OraclePrompts.PERSONA)
                .addUserMessageOfBlockParams(
                    listOf(
                        ContentBlockParam.ofImage(image),
                        ContentBlockParam.ofText(
                            TextBlockParam.builder().text(OraclePrompts.USER_INSTRUCTION).build()
                        ),
                    )
                )
                .build()

            val response = client.messages().create(params)
            val text = buildString {
                for (block in response.content()) {
                    block.text().ifPresent { append(it.text()) }
                }
            }.trim()
            return text.ifEmpty { "……" }
        } finally {
            runCatching { client.close() }
        }
    }
}
