/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.service.scripting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.activation.MimeType;
import jakarta.activation.MimeTypeParseException;

import com.top_logic.ai.service.OpenAIService;
import com.top_logic.basic.StringServices;
import com.top_logic.basic.io.StreamUtilities;
import com.top_logic.basic.io.binary.BinaryDataSource;
import com.top_logic.model.search.expr.config.operations.ScriptPrefix;
import com.top_logic.model.search.expr.config.operations.TLScriptFunctions;

import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.data.pdf.PdfFile;
import dev.langchain4j.data.video.Video;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * TL-Script functions for interacting with OpenAI API.
 *
 * <p>
 * This class provides functional-style access to OpenAI chat completions from TL-Script. All
 * functions automatically manage client pooling and thread context binding.
 * </p>
 *
 * <p>
 * Example usage in TL-Script:
 * </p>
 * 
 * <pre>
 * aiChat("What is TopLogic?")
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
	 *        The model name to use (e.g., "gpt-4o", "gpt-3.5-turbo"), or {@code null} to use
	 *        the default model configured in {@link OpenAIService}.
	 * @return The assistant's response text.
	 */
	public static String chat(List<Object> messages, String model) {
		ChatModel chatModel = OpenAIService.getInstance().getChatModel(model);

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
			return UserMessage.from(createContent(binary));
		} else if (content instanceof List<?> list) {
			// Mixed content: text and binary data
			List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
			for (Object item : list) {
				if (item instanceof BinaryDataSource binary) {
					contents.add(createContent(binary));
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
	 * Creates appropriate content from a {@link BinaryDataSource} based on its content type.
	 *
	 * <p>
	 * Supports:
	 * </p>
	 * <ul>
	 * <li>Images (image/*): Creates {@link ImageContent}</li>
	 * <li>Audio (audio/*): Creates {@link AudioContent}</li>
	 * <li>Video (video/*): Creates {@link VideoContent}</li>
	 * <li>PDF (application/pdf): Creates {@link PdfFileContent}</li>
	 * <li>Text (text/*): Creates {@link TextContent} by decoding the binary data as UTF-8</li>
	 * </ul>
	 *
	 * @param binary
	 *        The binary data source.
	 * @return The appropriate content type based on the MIME type.
	 * @throws RuntimeException
	 *         If the content type is not supported or reading fails.
	 */
	private static dev.langchain4j.data.message.Content createContent(BinaryDataSource binary) {
		try {
			String contentType = binary.getContentType();
			if (contentType == null || contentType.isEmpty()) {
				throw new IllegalArgumentException("Content type is required for binary data");
			}

			byte[] bytes = StreamUtilities.readStreamContents(binary);

			// Handle text content types by decoding with the specified charset
			if (contentType.startsWith("text/")) {
				String charset = extractCharset(contentType);
				String text = new String(bytes, charset);
				return new TextContent(text);
			}

			// For non-text content, encode as base64
			String base64 = Base64.getEncoder().encodeToString(bytes);

			// Determine content type from MIME type
			if (contentType.startsWith("image/")) {
				// Create image content
				Image image = Image.builder()
					.base64Data(base64)
					.mimeType(contentType)
					.build();
				return new ImageContent(image);
			} else if (contentType.startsWith("audio/")) {
				// Create audio content
				Audio audio = Audio.builder()
					.base64Data(base64)
					.mimeType(contentType)
					.build();
				return new AudioContent(audio);
			} else if (contentType.startsWith("video/")) {
				// Create video content
				Video video = Video.builder()
					.base64Data(base64)
					.mimeType(contentType)
					.build();
				return new VideoContent(video);
			} else if ("application/pdf".equals(contentType)) {
				// Create PDF content
				PdfFile pdfFile = PdfFile.builder()
					.base64Data(base64)
					.mimeType(contentType)
					.build();
				return new PdfFileContent(pdfFile);
			} else {
				throw new IllegalArgumentException(
					"Unsupported content type: " + contentType +
						". Supported types: image/*, audio/*, video/*, text/*, application/pdf");
			}
		} catch (IOException ex) {
			throw new RuntimeException("Failed to read binary data: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Extracts the charset from a MIME type string.
	 *
	 * <p>
	 * Parses the charset parameter from MIME types like "text/plain; charset=ISO-8859-1".
	 * If no charset is specified, defaults to UTF-8.
	 * </p>
	 *
	 * @param contentType
	 *        The MIME type string (e.g., "text/plain; charset=UTF-8").
	 * @return The charset name, defaults to "UTF-8" if not specified.
	 */
	private static String extractCharset(String contentType) {
		try {
			MimeType mimeType = new MimeType(contentType);
			String charset = mimeType.getParameter("charset");
			return charset != null ? charset : StringServices.UTF8;
		} catch (MimeTypeParseException ex) {
			// If parsing fails, fall back to UTF-8
			return StringServices.UTF8;
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
