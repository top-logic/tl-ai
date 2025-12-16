/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.service.scripting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

/**
 * Utility class for converting map-based schema definitions to LangChain4j JSON schemas.
 *
 * <p>
 * Converts TL-Script map structures into strongly-typed LangChain4j JSON schema objects.
 * Supports all JSON schema primitive types (string, integer, number, boolean), complex
 * types (array, object), enum values, and required property specifications.
 * </p>
 */
public class JsonSchemaConverter {

	/**
	 * Converts a map-based schema definition to a {@link JsonSchema}.
	 *
	 * @param schemaMap
	 *        Map containing "name" and "schema" fields defining the JSON schema.
	 * @return The constructed {@link JsonSchema}.
	 * @throws IllegalArgumentException
	 *         If the schema definition is invalid.
	 */
	public static JsonSchema fromMap(Map<?, ?> schemaMap) {
		String name = (String) schemaMap.get("name");
		if (name == null) {
			throw new IllegalArgumentException("JSON schema must contain a 'name' field.");
		}

		Object schema = schemaMap.get("schema");
		if (schema == null) {
			throw new IllegalArgumentException("JSON schema must contain a 'schema' field.");
		}

		if (!(schema instanceof Map<?, ?> schemaPropsMap)) {
			throw new IllegalArgumentException("Schema must be a Map.");
		}

		// Parse the root object schema with properties and required fields
		JsonObjectSchema.Builder rootBuilder = JsonObjectSchema.builder();

		// Get required properties list
		Object requiredObj = schemaPropsMap.get("required");
		List<String> requiredProps = new ArrayList<>();
		if (requiredObj instanceof List<?> reqList) {
			for (Object item : reqList) {
				if (item instanceof String str) {
					requiredProps.add(str);
				}
			}
		}

		// Parse and add properties
		Object propertiesObj = schemaPropsMap.get("properties");
		if (propertiesObj instanceof Map<?, ?> propertiesMap) {
			addPropertiesToBuilder(rootBuilder, propertiesMap);
		}

		// Add required properties if any
		if (!requiredProps.isEmpty()) {
			rootBuilder.required(requiredProps);
		}

		return JsonSchema.builder()
			.name(name)
			.rootElement(rootBuilder.build())
			.build();
	}

	/**
	 * Adds properties to a {@link JsonObjectSchema.Builder} using individual property methods.
	 *
	 * @param builder
	 *        The builder to add properties to.
	 * @param propertiesMap
	 *        The map of property definitions.
	 */
	private static void addPropertiesToBuilder(JsonObjectSchema.Builder builder, Map<?, ?> propertiesMap) {
		for (Map.Entry<?, ?> entry : propertiesMap.entrySet()) {
			String propertyName = (String) entry.getKey();
			Object propertyValue = entry.getValue();

			if (propertyValue instanceof Map<?, ?> propertyDef) {
				addProperty(builder, propertyName, propertyDef);
			}
		}
	}

	/**
	 * Adds a single property to a {@link JsonObjectSchema.Builder} based on its definition.
	 *
	 * @param builder
	 *        The builder to add the property to.
	 * @param propertyName
	 *        The name of the property.
	 * @param propertyDef
	 *        The property definition map containing "type", "description", etc.
	 */
	private static void addProperty(JsonObjectSchema.Builder builder, String propertyName,
			Map<?, ?> propertyDef) {
		String type = (String) propertyDef.get("type");

		if (type == null) {
			throw new IllegalArgumentException("Property '" + propertyName + "' must have a 'type' field.");
		}

		String description = (String) propertyDef.get("description");

		switch (type.toLowerCase()) {
			case "string":
				// Check for enum
				Object enumValues = propertyDef.get("enum");
				if (enumValues instanceof List<?> enumList) {
					List<String> enumStrings = extractStringList(enumList);
					if (description != null) {
						builder.addEnumProperty(propertyName, enumStrings, description);
					} else {
						builder.addEnumProperty(propertyName, enumStrings);
					}
				} else {
					// Regular string property
					if (description != null) {
						builder.addStringProperty(propertyName, description);
					} else {
						builder.addStringProperty(propertyName);
					}
				}
				break;

			case "integer":
				if (description != null) {
					builder.addIntegerProperty(propertyName, description);
				} else {
					builder.addIntegerProperty(propertyName);
				}
				break;

			case "number":
				if (description != null) {
					builder.addNumberProperty(propertyName, description);
				} else {
					builder.addNumberProperty(propertyName);
				}
				break;

			case "boolean":
				if (description != null) {
					builder.addBooleanProperty(propertyName, description);
				} else {
					builder.addBooleanProperty(propertyName);
				}
				break;

			case "array":
				builder.addProperty(propertyName, buildArraySchema(propertyDef));
				break;

			case "object":
				builder.addProperty(propertyName, buildObjectSchema(propertyDef));
				break;

			default:
				throw new IllegalArgumentException("Unsupported property type: " + type);
		}
	}

	/**
	 * Parses a schema element definition into a {@link JsonSchemaElement}.
	 *
	 * @param elementDef
	 *        The element definition map.
	 * @return The parsed schema element.
	 */
	private static JsonSchemaElement parseSchemaElement(Map<?, ?> elementDef) {
		String type = (String) elementDef.get("type");

		if (type == null) {
			throw new IllegalArgumentException("Schema element must have a 'type' field.");
		}

		switch (type.toLowerCase()) {
			case "string":
				return buildStringSchema(elementDef);

			case "integer":
				return buildIntegerSchema(elementDef);

			case "number":
				return buildNumberSchema(elementDef);

			case "boolean":
				return buildBooleanSchema(elementDef);

			case "array":
				return buildArraySchema(elementDef);

			case "object":
				return buildObjectSchema(elementDef);

			default:
				throw new IllegalArgumentException("Unsupported element type: " + type);
		}
	}

	/**
	 * Builds a string schema from a definition map.
	 */
	private static JsonStringSchema buildStringSchema(Map<?, ?> def) {
		JsonStringSchema.Builder builder = JsonStringSchema.builder();
		String description = (String) def.get("description");
		if (description != null) {
			builder.description(description);
		}
		return builder.build();
	}

	/**
	 * Builds an integer schema from a definition map.
	 */
	private static JsonIntegerSchema buildIntegerSchema(Map<?, ?> def) {
		JsonIntegerSchema.Builder builder = JsonIntegerSchema.builder();
		String description = (String) def.get("description");
		if (description != null) {
			builder.description(description);
		}
		return builder.build();
	}

	/**
	 * Builds a number schema from a definition map.
	 */
	private static JsonNumberSchema buildNumberSchema(Map<?, ?> def) {
		JsonNumberSchema.Builder builder = JsonNumberSchema.builder();
		String description = (String) def.get("description");
		if (description != null) {
			builder.description(description);
		}
		return builder.build();
	}

	/**
	 * Builds a boolean schema from a definition map.
	 */
	private static JsonBooleanSchema buildBooleanSchema(Map<?, ?> def) {
		JsonBooleanSchema.Builder builder = JsonBooleanSchema.builder();
		String description = (String) def.get("description");
		if (description != null) {
			builder.description(description);
		}
		return builder.build();
	}

	/**
	 * Builds an array schema from a definition map.
	 */
	private static JsonArraySchema buildArraySchema(Map<?, ?> def) {
		JsonArraySchema.Builder builder = JsonArraySchema.builder();
		String description = (String) def.get("description");
		if (description != null) {
			builder.description(description);
		}

		Object itemsObj = def.get("items");
		if (itemsObj instanceof Map<?, ?> itemsDef) {
			builder.items(parseSchemaElement(itemsDef));
		}

		return builder.build();
	}

	/**
	 * Builds an object schema from a definition map.
	 */
	private static JsonObjectSchema buildObjectSchema(Map<?, ?> def) {
		JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
		String description = (String) def.get("description");
		if (description != null) {
			builder.description(description);
		}

		// Add properties
		Object propertiesObj = def.get("properties");
		if (propertiesObj instanceof Map<?, ?> propertiesMap) {
			addPropertiesToBuilder(builder, propertiesMap);
		}

		// Add required properties
		Object requiredObj = def.get("required");
		if (requiredObj instanceof List<?> reqList) {
			List<String> requiredProps = extractStringList(reqList);
			if (!requiredProps.isEmpty()) {
				builder.required(requiredProps);
			}
		}

		return builder.build();
	}

	/**
	 * Extracts a list of strings from a generic list.
	 */
	private static List<String> extractStringList(List<?> list) {
		List<String> result = new ArrayList<>();
		for (Object item : list) {
			if (item instanceof String str) {
				result.add(str);
			}
		}
		return result;
	}
}
