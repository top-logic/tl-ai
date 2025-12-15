/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai.scripting;

import java.util.List;
import java.util.Map;

import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import com.top_logic.ai.openai.OpenAIService;
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
	 * @param model
	 *        The model to use (e.g., "gpt-4", "gpt-3.5-turbo").
	 * @param messages
	 *        A list of messages where each message is a list with role and content.
	 *        Example: [["user", "Hello"], ["assistant", "Hi!"], ["user", "How are you?"]]
	 * @return The assistant's response text.
	 */
	public static String chat(List<Object> messages, String model) {
		OpenAIClient client = OpenAIService.getInstance().getClient();

		ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder();
		
		for (Object message : messages) {
			if (message instanceof Map<?, ?> map) {
				String role = (String) map.get("role");
				String content = map.get("content").toString();

				switch (role.toLowerCase()) {
					case "system":
						builder.addMessage(
							ChatCompletionSystemMessageParam.builder()
								.content(ChatCompletionSystemMessageParam.Content.ofText(content))
								.build());
						break;
					case "user":
						builder.addMessage(
							ChatCompletionUserMessageParam.builder()
								.content(ChatCompletionUserMessageParam.Content.ofText(content))
								.build());
						break;
					case "assistant":
						builder.addMessage(
							ChatCompletionAssistantMessageParam.builder()
								.content(ChatCompletionAssistantMessageParam.Content.ofText(content))
								.build());
						break;
					default:
						throw new IllegalArgumentException("Unknown role: " + role + ". Must be 'system', 'user', or 'assistant'.");
				}
			} else {
				builder.addMessage(
					ChatCompletionUserMessageParam.builder()
						.content(ChatCompletionUserMessageParam.Content.ofText(message.toString()))
						.build());
			}

		}

		ChatCompletionCreateParams params = builder.model(ChatModel.of(model))
			.build();

		ChatCompletion completion = client.chat().completions().create(params);
		return completion.choices().get(0).message().content().orElse("");
	}
}
