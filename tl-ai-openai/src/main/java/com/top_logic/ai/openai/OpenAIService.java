/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

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

/**
 * TopLogic service that provides access to OpenAI API functionality.
 *
 * <p>
 * This service initializes an OpenAI client during application startup and ensures proper cleanup
 * during shutdown. The client can be configured through the TopLogic application configuration.
 * </p>
 *
 * <p>
 * The service provides a singleton instance that can be accessed via {@link #getInstance()} and
 * returns an initialized {@link OpenAIClient} ready to make API requests.
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
		Config.PROJECT,
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
		 * Configuration property name for project.
		 *
		 * @see #getProject()
		 */
		String PROJECT = "project";

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
		 * The OpenAI project ID.
		 *
		 * <p>
		 * Optional. Allows you to specify which project is used for an API request. Usage
		 * from these API requests will count against the specified project's quota.
		 * </p>
		 */
		@Name(PROJECT)
		@Nullable
		String getProject();
	}

	private static volatile OpenAIService _instance;

	private OpenAIClient _client;

	/**
	 * Creates a new {@link OpenAIService} from configuration.
	 *
	 * @param context
	 *        The instantiation context for error reporting.
	 * @param config
	 *        The service configuration.
	 */
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

			// Create OpenAI client builder with configured API key and base URL
			OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
				.apiKey(apiKey)
				.baseUrl(config.getBaseUrl());

			// Add optional organization if configured
			String organization = config.getOrganization();
			if (organization != null) {
				builder.organization(organization);
			}

			// Add optional project if configured
			String project = config.getProject();
			if (project != null) {
				builder.project(project);
			}

			// Build the client
			_client = builder.build();

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

			// Clean up OpenAI client resources
			if (_client != null) {
				// Note: The OpenAI Java client doesn't require explicit cleanup,
				// but we set it to null to allow garbage collection
				_client = null;
			}
		} finally {
			super.shutDown();
		}
	}

	/**
	 * Returns the initialized OpenAI client ready to make API requests.
	 *
	 * <p>
	 * The client is configured with the API key and base URL from the service configuration.
	 * You can use this client to interact with OpenAI models:
	 * </p>
	 *
	 * <pre>
	 * OpenAIClient client = OpenAIService.getInstance().getClient();
	 * ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
	 *     .model(ChatModel.GPT_4_O)
	 *     .addMessage(ChatCompletionMessageParam.ofChatCompletionUserMessageParam(
	 *         ChatCompletionUserMessageParam.builder()
	 *             .content(ChatCompletionUserMessageParam.Content.ofTextContent("Hello!"))
	 *             .build()))
	 *     .build();
	 * ChatCompletion response = client.chat().completions().create(params);
	 * </pre>
	 *
	 * @return The OpenAI client, or {@code null} if the service is not started.
	 */
	public OpenAIClient getClient() {
		return _client;
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
