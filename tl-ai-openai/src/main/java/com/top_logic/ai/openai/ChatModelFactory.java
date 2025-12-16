/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai;

import org.apache.commons.pool.BasePoolableObjectFactory;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * Factory for creating and managing pooled {@link ChatModel} instances.
 *
 * <p>
 * This factory is used by Apache Commons Pool to create, validate, and destroy
 * LangChain4j chat model instances in a thread-safe pool.
 * </p>
 */
class ChatModelFactory extends BasePoolableObjectFactory<ChatModel> {

	private final String _apiKey;

	private final String _baseUrl;

	private final String _organization;

	private final String _modelName;

	/**
	 * Creates a new {@link ChatModelFactory}.
	 *
	 * @param apiKey
	 *        The OpenAI API key.
	 * @param baseUrl
	 *        The base URL for the OpenAI API.
	 * @param organization
	 *        The optional organization ID.
	 * @param modelName
	 *        The model name to use for all instances.
	 */
	public ChatModelFactory(String apiKey, String baseUrl, String organization, String modelName) {
		_apiKey = apiKey;
		_baseUrl = baseUrl;
		_organization = organization;
		_modelName = modelName;
	}

	@Override
	public ChatModel makeObject() throws Exception {
		// Create LangChain4j OpenAI chat model
		OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
			.apiKey(_apiKey)
			.baseUrl(_baseUrl)
			.modelName(_modelName);

		// Add optional organization if configured
		if (_organization != null) {
			builder.organizationId(_organization);
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
