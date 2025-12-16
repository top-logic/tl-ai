/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Encrypted;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.basic.config.order.DisplayInherited;
import com.top_logic.basic.config.order.DisplayInherited.DisplayStrategy;
import com.top_logic.basic.config.order.DisplayOrder;
import com.top_logic.basic.module.ConfiguredManagedClass;
import com.top_logic.basic.module.TypedRuntimeModule;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * TopLogic service that provides access to OpenAI API functionality via LangChain4j.
 *
 * <p>
 * This service creates a {@link ChatModel} using LangChain4j's OpenAI integration. The model is
 * thread-safe and can be used concurrently from multiple sessions.
 * </p>
 *
 * <p>
 * The service provides a singleton instance that can be accessed via {@link #getInstance()} and
 * returns a {@link ChatModel} via {@link #getChatModel()}.
 * </p>
 */
public class OpenAIService extends ConfiguredManagedClass<OpenAIService.Config<?>> {

	/**
	 * Configuration interface for {@link OpenAIService}.
	 */
	@DisplayOrder({
		Config.API_KEY,
		Config.BASE_URL,
		Config.ORGANIZATION,
		Config.MODEL_NAME,
	})
	@DisplayInherited(DisplayStrategy.PREPEND)
	public interface Config<I extends OpenAIService> extends ConfiguredManagedClass.Config<I> {

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
		 * Configuration property name for default model name.
		 *
		 * @see #getModelName()
		 */
		String MODEL_NAME = "model-name";

		/**
		 * The OpenAI API key for authentication.
		 *
		 * <p>
		 * This key is required to make requests to the OpenAI API. You can obtain an API key
		 * from the OpenAI platform at https://platform.openai.com/api-keys
		 * </p>
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
		 * <p>
		 * You can override this to use a different endpoint, such as an Azure OpenAI deployment
		 * or a local proxy server.
		 * </p>
		 */
		@Name(BASE_URL)
		@StringDefault("https://api.openai.com/v1")
		String getBaseUrl();

		/**
		 * The OpenAI organization ID.
		 *
		 * <p>
		 * Optional. For users who belong to multiple organizations, you can specify which
		 * organization is used for an API request. Usage from these API requests will count
		 * against the specified organization's quota.
		 * </p>
		 */
		@Name(ORGANIZATION)
		@Nullable
		String getOrganization();

		/**
		 * The default model name to use.
		 *
		 * <p>
		 * Default: gpt-4o
		 * </p>
		 * <p>
		 * This is the default model used when no model is explicitly specified.
		 * Common values: gpt-4o, gpt-4-turbo, gpt-3.5-turbo
		 * </p>
		 */
		@Name(MODEL_NAME)
		@StringDefault("gpt-4o")
		String getModelName();
	}

	private static volatile OpenAIService _instance;

	private ChatModel _chatModel;

	/**
	 * Creates a new {@link OpenAIService} from configuration.
	 *
	 * @param context
	 *        The instantiation context for error reporting.
	 * @param config
	 *        The service configuration.
	 */
	@CalledByReflection
	public OpenAIService(InstantiationContext context, Config<?> config) {
		super(context, config);
	}

	/**
	 * Returns the singleton instance of the OpenAI service.
	 *
	 * @return The service instance, or {@code null} if not yet started.
	 */
	public static OpenAIService getInstance() {
		return _instance;
	}

	@Override
	protected void startUp() {
		super.startUp();

		try {
			// Register singleton instance
			_instance = this;

			Config<?> config = getConfig();

			// Validate API key is configured
			String apiKey = config.getApiKey();
			if (apiKey == null || apiKey.trim().isEmpty()) {
				throw new RuntimeException(
					"OpenAI API key must be configured. Please set the '" + Config.API_KEY +
					"' property in the service configuration.");
			}

			// Create LangChain4j OpenAI chat model
			OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
				.apiKey(apiKey)
				.baseUrl(config.getBaseUrl())
				.modelName(config.getModelName());

			// Add optional organization if configured
			String organization = config.getOrganization();
			if (organization != null) {
				builder.organizationId(organization);
			}

			_chatModel = builder.build();

		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new RuntimeException("Failed to start OpenAI service: " + ex.getMessage(), ex);
		}
	}

	@Override
	protected void shutDown() {
		try {
			// Unregister singleton instance
			_instance = null;

			// Clear the chat model reference
			_chatModel = null;

		} finally {
			super.shutDown();
		}
	}

	/**
	 * Returns the LangChain4j chat language model.
	 *
	 * <p>
	 * The model is thread-safe and can be used concurrently from multiple sessions.
	 * </p>
	 *
	 * @return The chat language model, or {@code null} if the service is not started.
	 */
	public ChatModel getChatModel() {
		return _chatModel;
	}

	/**
	 * Module for {@link OpenAIService}.
	 */
	public static final class Module extends TypedRuntimeModule<OpenAIService> {

		/**
		 * Singleton {@link OpenAIService.Module} instance.
		 */
		public static final OpenAIService.Module INSTANCE = new OpenAIService.Module();

		private Module() {
			// Singleton constructor
		}

		@Override
		public Class<OpenAIService> getImplementation() {
			return OpenAIService.class;
		}
	}
}
