/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai;

import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.impl.GenericObjectPool;

import com.openai.client.OpenAIClient;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.InteractionContext;
import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Encrypted;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.basic.config.annotation.defaults.IntDefault;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.basic.config.order.DisplayInherited;
import com.top_logic.basic.config.order.DisplayInherited.DisplayStrategy;
import com.top_logic.basic.config.order.DisplayOrder;
import com.top_logic.basic.module.ConfiguredManagedClass;
import com.top_logic.basic.module.TypedRuntimeModule;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.thread.UnboundListener;

/**
 * TopLogic service that provides access to OpenAI API functionality with thread-safe client pooling.
 *
 * <p>
 * This service manages a pool of {@link OpenAIClient} instances to support concurrent access from
 * multiple sessions. Clients are automatically bound to the current {@link ThreadContext} and
 * returned to the pool when the context is destroyed.
 * </p>
 *
 * <p>
 * The service provides a singleton instance that can be accessed via {@link #getInstance()} and
 * returns an {@link OpenAIClient} bound to the current thread context via {@link #getClient()}.
 * </p>
 */
public class OpenAIService extends ConfiguredManagedClass<OpenAIService.Config<?>> {

	/**
	 * ThreadContext property key for storing the borrowed client.
	 */
	private static final Property<OpenAIClient> CONTEXT_CLIENT_KEY =
		TypedAnnotatable.property(OpenAIClient.class, OpenAIService.class.getName() + ".client");

	/**
	 * Configuration interface for {@link OpenAIService}.
	 */
	@DisplayOrder({
		Config.API_KEY,
		Config.BASE_URL,
		Config.ORGANIZATION,
		Config.PROJECT,
		Config.MAX_POOL_SIZE,
		Config.MAX_IDLE_CLIENTS,
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
		 * Configuration property name for maximum pool size.
		 *
		 * @see #getMaxPoolSize()
		 */
		String MAX_POOL_SIZE = "max-pool-size";

		/**
		 * Configuration property name for maximum idle clients.
		 *
		 * @see #getMaxIdleClients()
		 */
		String MAX_IDLE_CLIENTS = "max-idle-clients";

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

		/**
		 * The maximum number of clients in the pool.
		 *
		 * <p>
		 * Default: 10
		 * </p>
		 * <p>
		 * This limits the total number of OpenAI client instances that can be created.
		 * If all clients are in use, additional requests will wait for a client to become available.
		 * </p>
		 */
		@Name(MAX_POOL_SIZE)
		@IntDefault(10)
		int getMaxPoolSize();

		/**
		 * The maximum number of idle clients kept in the pool.
		 *
		 * <p>
		 * Default: 5
		 * </p>
		 * <p>
		 * Idle clients exceeding this number will be destroyed to free resources.
		 * </p>
		 */
		@Name(MAX_IDLE_CLIENTS)
		@IntDefault(5)
		int getMaxIdleClients();
	}

	private static volatile OpenAIService _instance;

	private ObjectPool<OpenAIClient> _clientPool;

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

			// Create client factory
			OpenAIClientFactory factory = new OpenAIClientFactory(
				apiKey,
				config.getBaseUrl(),
				config.getOrganization(),
				config.getProject());

			// Create and configure the pool
			GenericObjectPool<OpenAIClient> pool = new GenericObjectPool<>(factory);
			pool.setMaxActive(config.getMaxPoolSize());
			pool.setMaxIdle(config.getMaxIdleClients());
			pool.setTestOnBorrow(true);
			pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_BLOCK);

			_clientPool = pool;

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

			// Close the client pool
			if (_clientPool != null) {
				try {
					_clientPool.close();
				} catch (Exception ex) {
					// Log but don't fail shutdown
					System.err.println("Error closing OpenAI client pool: " + ex.getMessage());
				}
				_clientPool = null;
			}
		} finally {
			super.shutDown();
		}
	}

	/**
	 * Returns an OpenAI client bound to the current {@link ThreadContext}.
	 *
	 * <p>
	 * The client is borrowed from the pool and automatically returned when the current
	 * {@link ThreadContext} is destroyed. Multiple calls to this method within the same
	 * thread context will return the same client instance.
	 * </p>
	 *
	 * <p>
	 * Example usage:
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
	 * @return The OpenAI client bound to the current thread context.
	 * @throws RuntimeException
	 *         If no client is available or the service is not started.
	 */
	public OpenAIClient getClient() {
		InteractionContext interaction = ThreadContextManager.getInteraction();
		if (interaction == null) {
			throw new RuntimeException(
				"No ThreadContext available. OpenAI clients must be used within an InteractionContext.");
		}

		// Check if we already have a client for this thread context
		OpenAIClient client = interaction.get(CONTEXT_CLIENT_KEY);
		if (client != null) {
			return client;
		}

		// Borrow a new client from the pool
		final OpenAIClient newClient;
		try {
			newClient = _clientPool.borrowObject();
		} catch (Exception ex) {
			throw new RuntimeException("Failed to borrow OpenAI client from pool: " + ex.getMessage(), ex);
		}

		// Store in thread context
		interaction.set(CONTEXT_CLIENT_KEY, client);

		// Register cleanup callback to return client to pool when context is destroyed
		interaction.addUnboundListener(new UnboundListener() {
			@Override
			public void threadUnbound(InteractionContext context) throws Throwable {
				try {
					_clientPool.returnObject(newClient);
				} catch (Exception ex) {
					// Log but don't fail cleanup
					System.err.println("Error returning OpenAI client to pool: " + ex.getMessage());
				}
			}
		});

		return client;
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
