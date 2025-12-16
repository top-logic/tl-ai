/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai.scripting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.top_logic.ai.openai.OpenAIService;
import com.top_logic.basic.io.StreamUtilities;
import com.top_logic.basic.io.binary.BinaryDataSource;
import com.top_logic.model.search.expr.config.operations.ScriptPrefix;
import com.top_logic.model.search.expr.config.operations.TLScriptFunctions;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

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
		ChatModel chatModel = OpenAIService.getInstance().getChatModel();

		List<ChatMessage> chatMessages = new ArrayList<>();

		for (Object message : messages) {
			if (message instanceof Map<?, ?> map) {
				String role = (String) map.get("role");
				Object content = map.get("content");

				switch (role.toLowerCase()) {
					case "system":
						chatMessages.add(new SystemMessage(toString(content)));
						break;
					case "user":
						chatMessages.add(createUserMessage(content));
						break;
					case "assistant":
						chatMessages.add(new AiMessage(toString(content)));
						break;
					default:
						throw new IllegalArgumentException("Unknown role: " + role + ". Must be 'system', 'user', or 'assistant'.");
				}
			} else {
				chatMessages.add(createUserMessage(message));
			}
		}

		ChatResponse response = chatModel.chat(chatMessages);
		return response.aiMessage().text();
	}

	/**
	 * Creates a user message from mixed content (text, images, documents).
	 *
	 * @param content
	 *        The content - can be String, binary data, or a list of mixed content.
	 * @return The user message.
	 */
	private static UserMessage createUserMessage(Object content) {
		if (content instanceof String text) {
			return new UserMessage(text);
		} else if (content instanceof BinaryDataSource binary) {
			return UserMessage.from(createImageContent(binary));
		} else if (content instanceof List<?> list) {
			// Mixed content: text and images
			List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
			for (Object item : list) {
				if (item instanceof String text) {
					contents.add(new TextContent(text));
				} else if (item instanceof BinaryDataSource binary) {
					contents.add(createImageContent(binary));
				} else {
					contents.add(new TextContent(toString(item)));
				}
			}
			return UserMessage.from(contents);
		} else {
			return new UserMessage(toString(content));
		}
	}

	/**
	 * Creates an image content from a {@link BinaryDataSource}.
	 *
	 * @param binary
	 *        The binary data (image or document).
	 * @return The image content.
	 */
	private static ImageContent createImageContent(BinaryDataSource binary) {
		try {
			// Read binary data and encode as base64
			byte[] bytes = StreamUtilities.readStreamContents(binary);
			String base64 = Base64.getEncoder().encodeToString(bytes);

			String contentType = binary.getContentType();
			String dataUrl = "data:" + contentType + ";base64," + base64;

			return new ImageContent(dataUrl);
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
