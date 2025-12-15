/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai.scripting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import com.top_logic.ai.openai.OpenAIService;
import com.top_logic.basic.io.StreamUtilities;
import com.top_logic.basic.io.binary.BinaryDataSource;
import com.top_logic.model.search.expr.config.operations.ScriptPrefix;
import com.top_logic.model.search.expr.config.operations.TLScriptFunctions;

/**
 * TL-Script functions for interacting with OpenAI API.
 *
 * <p>
 * This class provides functional-style access to OpenAI chat completions from TL-Script.
 * All functions automatically manage client pooling and thread context binding.
 * </p>
 *
 * <p>
 * Example usage in TL-Script:
 * </p>
 * <pre>
 * openai:chat("gpt-4", "What is TopLogic?")
 * openai:chatWithSystem("gpt-4", "You are a helpful assistant.", "Explain MCP protocol.")
 * </pre>
 */
@ScriptPrefix("ai")
public class OpenAIScriptFunctions extends TLScriptFunctions {

	/**
	 * Sends a multi-turn conversation to the chat completion API.
	 *
	 * <p>
	 * Messages can be strings, maps with role/content, or lists. Content can be text strings or
	 * {@link BinaryDataSource} for images and documents.
	 * </p>
	 *
	 * @param messages
	 *        A list of messages. Each message can be:
	 *        <ul>
	 *        <li>String: Treated as user text message</li>
	 *        <li>Map with "role" and "content": Explicit role (system/user/assistant). Content can
	 *        be string, binary data, or a list of mixed content.</li>
	 *        <li>Binary data: Treated as user image/document message</li>
	 *        </ul>
	 * @param model
	 *        The model to use (e.g., "gpt-4o", "gpt-4-vision-preview").
	 * @return The assistant's response text.
	 */
	public static String chat(List<Object> messages, String model) {
		OpenAIClient client = OpenAIService.getInstance().getClient();

		ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder();

		for (Object message : messages) {
			if (message instanceof Map<?, ?> map) {
				String role = (String) map.get("role");
				Object content = map.get("content");

				switch (role.toLowerCase()) {
					case "system":
						builder.addMessage(
							ChatCompletionSystemMessageParam.builder()
								.content(ChatCompletionSystemMessageParam.Content.ofText(toString(content)))
								.build());
						break;
					case "user":
						builder.addMessage(createUserMessage(content));
						break;
					case "assistant":
						builder.addMessage(
							ChatCompletionAssistantMessageParam.builder()
								.content(ChatCompletionAssistantMessageParam.Content.ofText(toString(content)))
								.build());
						break;
					default:
						throw new IllegalArgumentException("Unknown role: " + role + ". Must be 'system', 'user', or 'assistant'.");
				}
			} else {
				builder.addMessage(createUserMessage(message));
			}
		}

		ChatCompletionCreateParams params = builder.model(ChatModel.of(model))
			.build();

		ChatCompletion completion = client.chat().completions().create(params);
		return completion.choices().get(0).message().content().orElse("");
	}

	/**
	 * Creates a user message from mixed content (text, images, documents).
	 *
	 * @param content
	 *        The content - can be String, binary data, or a list of mixed content.
	 * @return The chat completion message parameter.
	 */
	private static ChatCompletionMessageParam createUserMessage(Object content) {
		ChatCompletionUserMessageParam message;

		if (content instanceof String text) {
			message = ChatCompletionUserMessageParam.builder()
				.content(ChatCompletionUserMessageParam.Content.ofText(text))
				.build();
		} else if (content instanceof BinaryDataSource binary) {
			message = ChatCompletionUserMessageParam.builder()
				.content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(
					List.of(createImagePart(binary))))
				.build();
		} else if (content instanceof List<?> list) {
			// Mixed content: text and images
			List<ChatCompletionContentPart> parts = new ArrayList<>();
			for (Object item : list) {
				if (item instanceof String text) {
					parts.add(ChatCompletionContentPart.ofText(
						ChatCompletionContentPartText.builder()
							.text(text)
							.build()));
				} else if (item instanceof BinaryDataSource binary) {
					parts.add(createImagePart(binary));
				} else {
					parts.add(ChatCompletionContentPart.ofText(
						ChatCompletionContentPartText.builder()
							.text(toString(item))
							.build()));
				}
			}
			message = ChatCompletionUserMessageParam.builder()
				.content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(parts))
				.build();
		} else {
			message = ChatCompletionUserMessageParam.builder()
				.content(ChatCompletionUserMessageParam.Content.ofText(toString(content)))
				.build();
		}

		return ChatCompletionMessageParam.ofUser(message);
	}

	/**
	 * Creates an image content part from a {@link BinaryDataSource}.
	 *
	 * @param binary
	 *        The binary data (image or document).
	 * @return The image content part.
	 */
	private static ChatCompletionContentPart createImagePart(BinaryDataSource binary) {
		try {
			// Read binary data and encode as base64
			byte[] bytes = StreamUtilities.readStreamContents(binary);
			String base64 = Base64.getEncoder().encodeToString(bytes);

			String contentType = binary.getContentType();
			String dataUrl = "data:" + contentType + ";base64," + base64;

			ChatCompletionContentPartImage imagePart = ChatCompletionContentPartImage.builder()
				.imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
					.url(dataUrl)
					.build())
				.build();

			return ChatCompletionContentPart.ofImageUrl(imagePart);
		} catch (IOException ex) {
			throw new RuntimeException("Failed to read binary data: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Converts content to string, handling various types.
	 *
	 * @param content
	 *        The content object.
	 * @return The string representation.
	 */
	private static String toString(Object content) {
		if (content instanceof String) {
			return (String) content;
		}
		return content != null ? content.toString() : "";
	}
}
