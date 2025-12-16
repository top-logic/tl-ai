/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai;

import java.util.List;
import java.util.stream.Collectors;

import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Encrypted;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.StringDefault;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.mistralai.internal.client.DefaultMistralAiClient;

/**
 * Factory for creating Mistral AI chat model instances.
 *
 * <p>
 * This factory creates {@link ChatModel} instances using the Mistral AI API
 * for models like Mistral Large, Mistral Medium, etc.
 * </p>
 */
public class MistralChatModelFactory extends ChatModelFactory {

	/**
	 * Configuration interface for {@link MistralChatModelFactory}.
	 */
	public interface Config extends ChatModelFactory.Config<MistralChatModelFactory> {

		/**
		 * Configuration property name for API key.
		 *
		 * @see #getApiKey()
		 */
		String API_KEY = "api-key";

		/**
		 * Configuration property name for base URL.
		 *
		 * @see #getBaseUrl()
		 */
		String BASE_URL = "base-url";

		/**
		 * The Mistral AI API key for authentication.
		 */
		@Name(API_KEY)
		@Encrypted
		String getApiKey();

		/**
		 * The base URL for the Mistral AI API.
		 *
		 * <p>
		 * Default: https://api.mistral.ai/v1
		 * </p>
		 */
		@Name(BASE_URL)
		@StringDefault("https://api.mistral.ai/v1")
		String getBaseUrl();
	}

	/**
	 * Creates a new {@link MistralChatModelFactory} from configuration.
	 *
	 * @param context
	 *        The instantiation context for error reporting.
	 * @param config
	 *        The factory configuration.
	 */
	public MistralChatModelFactory(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	public Config getConfig() {
		return (Config) super.getConfig();
	}

	@Override
	public boolean hasValidConfiguration() {
		return isValidApiKey(getConfig().getApiKey());
	}

	@Override
	protected ChatModel createModel(String modelName) throws Exception {
		Config config = getConfig();

		// Create LangChain4j Mistral AI chat model
		MistralAiChatModel.MistralAiChatModelBuilder builder = MistralAiChatModel.builder()
			.apiKey(config.getApiKey())
			.baseUrl(config.getBaseUrl())
			.modelName(modelName);

		return builder.build();
	}

	@Override
	protected List<String> getAvailableModels() throws Exception {
		Config config = getConfig();

		// Create a Mistral AI client to list available models
		DefaultMistralAiClient client = DefaultMistralAiClient.builder()
			.apiKey(config.getApiKey())
			.baseUrl(config.getBaseUrl())
			.build();

		// Query the API for available models and extract their IDs
		return client.listModels().getData().stream()
			.map(model -> model.getId())
			.collect(Collectors.toList());
	}
}
