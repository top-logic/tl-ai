/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai;

import org.apache.commons.pool.BasePoolableObjectFactory;

import com.top_logic.basic.config.ConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Encrypted;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.basic.config.annotation.defaults.IntDefault;
import com.top_logic.basic.config.annotation.defaults.StringDefault;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * Factory for creating and managing pooled {@link ChatModel} instances.
 *
 * <p>
 * This factory is used by Apache Commons Pool to create, validate, and destroy
 * LangChain4j chat model instances in a thread-safe pool. Each factory is configured
 * for a specific AI provider and model.
 * </p>
 */
public class ChatModelFactory extends BasePoolableObjectFactory<ChatModel>
		implements ConfiguredInstance<ChatModelFactory.Config> {

	/**
	 * Configuration interface for {@link ChatModelFactory}.
	 */
	public interface Config extends PolymorphicConfiguration<ChatModelFactory> {

		/**
		 * Configuration property name for model name.
		 *
		 * @see #getModelName()
		 */
		String MODEL_NAME = "model-name";

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
		 * Configuration property name for organization.
		 *
		 * @see #getOrganization()
		 */
		String ORGANIZATION = "organization";

		/**
		 * Configuration property name for maximum pool size.
		 *
		 * @see #getMaxPoolSize()
		 */
		String MAX_POOL_SIZE = "max-pool-size";

		/**
		 * Configuration property name for maximum idle models.
		 *
		 * @see #getMaxIdleModels()
		 */
		String MAX_IDLE_MODELS = "max-idle-models";

		/**
		 * The model name (e.g., "gpt-4o", "gpt-3.5-turbo").
		 */
		@Name(MODEL_NAME)
		String getModelName();

		/**
		 * The OpenAI API key for authentication.
		 */
		@Name(API_KEY)
		@Encrypted
		String getApiKey();

		/**
		 * The base URL for the OpenAI API.
		 *
		 * <p>
		 * Default: https://api.openai.com/v1
		 * </p>
		 */
		@Name(BASE_URL)
		@StringDefault("https://api.openai.com/v1")
		String getBaseUrl();

		/**
		 * The OpenAI organization ID.
		 *
		 * <p>
		 * Optional. For users who belong to multiple organizations.
		 * </p>
		 */
		@Name(ORGANIZATION)
		@Nullable
		String getOrganization();

		/**
		 * The maximum number of chat models in the pool.
		 *
		 * <p>
		 * Default: 10
		 * </p>
		 */
		@Name(MAX_POOL_SIZE)
		@IntDefault(10)
		int getMaxPoolSize();

		/**
		 * The maximum number of idle chat models kept in the pool.
		 *
		 * <p>
		 * Default: 5
		 * </p>
		 */
		@Name(MAX_IDLE_MODELS)
		@IntDefault(5)
		int getMaxIdleModels();
	}

	private final Config _config;

	/**
	 * Creates a new {@link ChatModelFactory} from configuration.
	 *
	 * @param context
	 *        The instantiation context for error reporting.
	 * @param config
	 *        The factory configuration.
	 */
	public ChatModelFactory(InstantiationContext context, Config config) {
		_config = config;
	}

	@Override
	public Config getConfig() {
		return _config;
	}

	@Override
	public ChatModel makeObject() throws Exception {
		// Create LangChain4j OpenAI chat model
		OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
			.apiKey(_config.getApiKey())
			.baseUrl(_config.getBaseUrl())
			.modelName(_config.getModelName());

		// Add optional organization if configured
		String organization = _config.getOrganization();
		if (organization != null) {
			builder.organizationId(organization);
		}

		return builder.build();
	}

	@Override
	public void destroyObject(ChatModel model) throws Exception {
		// ChatModel doesn't have an explicit close method in LangChain4j
		// The underlying HTTP client will be cleaned up by garbage collection
	}

	@Override
	public boolean validateObject(ChatModel model) {
		// Basic validation - ensure model is not null
		return model != null;
	}
}
