/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai;

import org.apache.commons.pool.BasePoolableObjectFactory;

import com.top_logic.basic.config.ConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.IntDefault;

import dev.langchain4j.model.chat.ChatModel;

/**
 * Abstract factory for creating and managing pooled {@link ChatModel} instances.
 *
 * <p>
 * This factory is used by Apache Commons Pool to create, validate, and destroy
 * LangChain4j chat model instances in a thread-safe pool. Each factory is configured
 * for a specific AI provider and model.
 * </p>
 *
 * <p>
 * Concrete implementations exist for different AI providers:
 * </p>
 * <ul>
 * <li>{@link OpenAIChatModelFactory}: For OpenAI models (GPT-4o, GPT-3.5-turbo, etc.)</li>
 * <li>{@link AnthropicChatModelFactory}: For Anthropic models (Claude, etc.)</li>
 * <li>{@link MistralChatModelFactory}: For Mistral AI models</li>
 * </ul>
 */
public abstract class ChatModelFactory extends BasePoolableObjectFactory<ChatModel>
		implements ConfiguredInstance<ChatModelFactory.Config<?>> {

	/**
	 * Configuration interface for {@link ChatModelFactory}.
	 */
	public interface Config<I extends ChatModelFactory> extends PolymorphicConfiguration<I> {

		/**
		 * Configuration property name for model name.
		 *
		 * @see #getModelName()
		 */
		String MODEL_NAME = "model-name";

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
		 * The model name (e.g., "gpt-4o", "claude-3-opus", "mistral-large").
		 *
		 * <p>
		 * This serves as the unique identifier for this factory and determines which
		 * model is used when calling {@link OpenAIService#getChatModel(String)}.
		 * </p>
		 */
		@Name(MODEL_NAME)
		String getModelName();

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

	private final Config<?> _config;

	/**
	 * Creates a new {@link ChatModelFactory} from configuration.
	 *
	 * @param context
	 *        The instantiation context for error reporting.
	 * @param config
	 *        The factory configuration.
	 */
	protected ChatModelFactory(InstantiationContext context, Config<?> config) {
		_config = config;
	}

	@Override
	public Config<?> getConfig() {
		return _config;
	}

	/**
	 * Checks if this factory has a valid configuration.
	 *
	 * <p>
	 * This method is used during service startup to filter out factories that cannot be used
	 * because required configuration (like API keys) is missing or invalid.
	 * </p>
	 *
	 * @return {@code true} if the factory is properly configured and can create models,
	 *         {@code false} otherwise.
	 */
	public abstract boolean hasValidConfiguration();

	/**
	 * Checks if an API key is valid (not null, not empty, not a placeholder).
	 *
	 * @param apiKey
	 *        The API key to check.
	 * @return {@code true} if the API key is valid, {@code false} otherwise.
	 */
	protected static boolean isValidApiKey(String apiKey) {
		if (apiKey == null || apiKey.trim().isEmpty()) {
			return false;
		}
		// Check if it's a placeholder like %OPENAI_API_KEY%
		if (apiKey.startsWith("%") && apiKey.endsWith("%")) {
			return false;
		}
		return true;
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
