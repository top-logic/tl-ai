/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai;

import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.impl.GenericObjectPool;

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
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.thread.UnboundListener;

import dev.langchain4j.model.chat.ChatModel;

/**
 * TopLogic service that provides access to OpenAI API functionality via LangChain4j with thread-safe client pooling.
 *
 * <p>
 * This service manages a pool of {@link ChatModel} instances to support concurrent access from
 * multiple sessions. Models are automatically bound to the current {@link InteractionContext} and
 * returned to the pool when the context is destroyed.
 * </p>
 *
 * <p>
 * The service provides a singleton instance that can be accessed via {@link #getInstance()} and
 * returns a {@link ChatModel} bound to the current thread context via {@link #getChatModel()}.
 * </p>
 */
public class OpenAIService extends ConfiguredManagedClass<OpenAIService.Config<?>> {

	/**
	 * ThreadContext property key for storing the borrowed chat model.
	 */
	private static final Property<ChatModel> CONTEXT_MODEL_KEY =
		TypedAnnotatable.property(ChatModel.class, OpenAIService.class.getName() + ".model");

	/**
	 * Configuration interface for {@link OpenAIService}.
	 */
	@DisplayOrder({
		Config.API_KEY,
		Config.BASE_URL,
		Config.ORGANIZATION,
		Config.MODEL_NAME,
		Config.MAX_POOL_SIZE,
		Config.MAX_IDLE_MODELS,
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

		/**
		 * The maximum number of chat models in the pool.
		 *
		 * <p>
		 * Default: 10
		 * </p>
		 * <p>
		 * This limits the total number of chat model instances that can be created.
		 * If all models are in use, additional requests will wait for a model to become available.
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
		 * <p>
		 * Idle models exceeding this number will be destroyed to free resources.
		 * </p>
		 */
		@Name(MAX_IDLE_MODELS)
		@IntDefault(5)
		int getMaxIdleModels();
	}

	private static volatile OpenAIService _instance;

	private ObjectPool<ChatModel> _modelPool;

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

			// Create model factory
			ChatModelFactory factory = new ChatModelFactory(
				apiKey,
				config.getBaseUrl(),
				config.getOrganization(),
				config.getModelName());

			// Create and configure the pool
			GenericObjectPool<ChatModel> pool = new GenericObjectPool<>(factory);
			pool.setMaxActive(config.getMaxPoolSize());
			pool.setMaxIdle(config.getMaxIdleModels());
			pool.setTestOnBorrow(true);
			pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_BLOCK);

			_modelPool = pool;

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

			// Close the model pool
			if (_modelPool != null) {
				try {
					_modelPool.close();
				} catch (Exception ex) {
					// Log but don't fail shutdown
					System.err.println("Error closing chat model pool: " + ex.getMessage());
				}
				_modelPool = null;
			}
		} finally {
			super.shutDown();
		}
	}

	/**
	 * Returns a chat model bound to the current {@link InteractionContext}.
	 *
	 * <p>
	 * The model is borrowed from the pool and automatically returned when the current
	 * {@link InteractionContext} is destroyed. Multiple calls to this method within the same
	 * interaction context will return the same model instance.
	 * </p>
	 *
	 * @return The chat model bound to the current interaction context.
	 * @throws RuntimeException
	 *         If no model is available or the service is not started.
	 */
	public ChatModel getChatModel() {
		InteractionContext interaction = ThreadContextManager.getInteraction();
		if (interaction == null) {
			throw new RuntimeException(
				"No InteractionContext available. Chat models must be used within an InteractionContext.");
		}

		// Check if we already have a model for this interaction context
		ChatModel model = interaction.get(CONTEXT_MODEL_KEY);
		if (model != null) {
			return model;
		}

		// Borrow a new model from the pool
		final ChatModel newModel;
		try {
			newModel = _modelPool.borrowObject();
		} catch (Exception ex) {
			throw new RuntimeException("Failed to borrow chat model from pool: " + ex.getMessage(), ex);
		}

		// Store in interaction context
		interaction.set(CONTEXT_MODEL_KEY, newModel);

		// Register cleanup callback to return model to pool when context is destroyed
		interaction.addUnboundListener(new UnboundListener() {
			@Override
			public void threadUnbound(InteractionContext context) throws Throwable {
				try {
					_modelPool.returnObject(newModel);
				} catch (Exception ex) {
					// Log but don't fail cleanup
					System.err.println("Error returning chat model to pool: " + ex.getMessage());
				}
			}
		});

		return newModel;
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
