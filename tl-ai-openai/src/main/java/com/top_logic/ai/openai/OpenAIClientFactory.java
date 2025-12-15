/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.openai;

import org.apache.commons.pool.BasePoolableObjectFactory;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

/**
 * Factory for creating and managing pooled {@link OpenAIClient} instances.
 *
 * <p>
 * This factory is used by Apache Commons Pool to create, validate, and destroy
 * OpenAI client instances in a thread-safe pool.
 * </p>
 */
class OpenAIClientFactory extends BasePoolableObjectFactory<OpenAIClient> {

	private final String _apiKey;

	private final String _baseUrl;

	private final String _organization;

	private final String _project;

	/**
	 * Creates a new {@link OpenAIClientFactory}.
	 *
	 * @param apiKey
	 *        The OpenAI API key.
	 * @param baseUrl
	 *        The base URL for the OpenAI API.
	 * @param organization
	 *        The optional organization ID.
	 * @param project
	 *        The optional project ID.
	 */
	public OpenAIClientFactory(String apiKey, String baseUrl, String organization, String project) {
		_apiKey = apiKey;
		_baseUrl = baseUrl;
		_organization = organization;
		_project = project;
	}

	@Override
	public OpenAIClient makeObject() throws Exception {
		// Create OpenAI client builder with configured API key and base URL
		OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
			.apiKey(_apiKey)
			.baseUrl(_baseUrl);

		// Add optional organization if configured
		if (_organization != null) {
			builder.organization(_organization);
		}

		// Add optional project if configured
		if (_project != null) {
			builder.project(_project);
		}

		// Build and return the client
		return builder.build();
	}

	@Override
	public void destroyObject(OpenAIClient client) throws Exception {
		client.close();
	}

	@Override
	public boolean validateObject(OpenAIClient client) {
		// Basic validation - ensure client is not null
		return client != null;
	}
}
