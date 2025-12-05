/*
 * Copyright (c) 2025 TopLogic. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.tools;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.top_logic.ai.mcp.server.util.JsonResponseBuilder;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.util.ResKey;
import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLType;
import com.top_logic.model.util.TLModelNamingConvention;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * MCP tool for creating new TopLogic enumerations with optional classifiers.
 * <p>
 * This tool provides functionality to create new enumerations in TopLogic application model modules
 * using {@link TLModelUtil#addEnumeration(TLModule, String)}. If an enumeration with the given name
 * already exists in the module, the existing enumeration is returned.
 * </p>
 * <p>
 * Classifiers can be optionally specified and will be created along with new enumerations. When an
 * enumeration already exists, the classifiers parameter is ignored (idempotent creation).
 * </p>
 */
public class EnumerationCreationTool {

	/** Tool name for the enumeration creation functionality. */
	public static final String TOOL_NAME = "create-enumeration";

	/** Tool description. */
	private static final String DESCRIPTION =
		"Create a new TopLogic enumeration with optional classifiers, or get an existing one by name";

	/** JSON Schema for the tool input. */
	private static final String INPUT_SCHEMA_JSON = """
			{
			  "type": "object",
			  "properties": {
			    "moduleName": {
			      "type": "string",
			      "description": "Name of the module where the enumeration will be created"
			    },
			    "enumName": {
			      "type": "string",
			      "description": "Name of the enumeration. Must be a valid Java identifier and may contain dots."
			    },
			    "label": {
			      "type": "object",
			      "properties": {
			        "en": {
			          "type": "string",
			          "description": "English label for the enumeration"
			        },
			        "de": {
			          "type": "string",
			          "description": "German label for the enumeration"
			        }
			      },
			      "required": ["en", "de"],
			      "additionalProperties": false
			    },
			    "description": {
			      "type": "object",
			      "properties": {
			        "en": {
			          "type": "string",
			          "description": "English description for the enumeration"
			        },
			        "de": {
			          "type": "string",
			          "description": "German description for the enumeration"
			        }
			      },
			      "required": ["en", "de"],
			      "additionalProperties": false
			    },
			    "classifiers": {
			      "type": "array",
			      "description": "List of classifiers to create within this enumeration",
			      "items": {
			        "type": "object",
			        "properties": {
			          "classifierName": {
			            "type": "string",
			            "description": "Name of the classifier. Must start with a letter or underscore, may contain letters, numbers, dots, underscores, and minus signs, and must not end with a dot."
			          },
			          "default": {
			            "type": "boolean",
			            "description": "Whether this is the default classifier (default: false)"
			          },
			          "label": {
			            "type": "object",
			            "properties": {
			              "en": {
			                "type": "string",
			                "description": "English label for the classifier"
			              },
			              "de": {
			                "type": "string",
			                "description": "German label for the classifier"
			              }
			            },
			            "required": ["en", "de"],
			            "additionalProperties": false
			          },
			          "description": {
			            "type": "object",
			            "properties": {
			              "en": {
			                "type": "string",
			                "description": "English description for the classifier"
			              },
			              "de": {
			                "type": "string",
			                "description": "German description for the classifier"
			              }
			            },
			            "required": ["en", "de"],
			            "additionalProperties": false
			          }
			        },
			        "required": ["classifierName"],
			        "additionalProperties": false
			      }
			    }
			  },
			  "required": ["moduleName", "enumName"],
			  "additionalProperties": false
			}
			""";

	/**
	 * Pattern for validating enumeration names.
	 * <p>
	 * Based on {@code PartNameConstraints.MANDATORY_TYPE_NAME_PATTERN}:
	 * Must be a valid Java identifier and may contain dots.
	 * </p>
	 */
	private static final Pattern ENUM_NAME_PATTERN = Pattern.compile(
		"^\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(?:\\.\\p{javaJavaIdentifierPart}+)*$");

	/**
	 * Pattern for validating classifier names.
	 * <p>
	 * Based on {@code PartNameConstraints.MANDATORY_TYPE_PART_NAME_PATTERN}:
	 * Must start with a letter or underscore, may contain letters, numbers, dots, underscores,
	 * and minus signs, and must not end with a dot.
	 * </p>
	 */
	private static final Pattern CLASSIFIER_NAME_PATTERN = Pattern.compile(
		"^\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(?:[\\.\\-]\\p{javaJavaIdentifierPart}+)*$");

	/**
	 * Internal record for holding parsed classifier data before transaction.
	 *
	 * @param name
	 *        The classifier name.
	 * @param isDefault
	 *        Whether this classifier is the default.
	 * @param i18n
	 *        Localized label and description texts.
	 */
	private record ClassifierData(
		String name,
		boolean isDefault,
		ToolI18NUtil.LocalizedTexts i18n) {
	}

	/**
	 * Creates the MCP tool specification for enumeration creation.
	 *
	 * @return The tool specification that can be registered with the MCP server.
	 */
	public static McpServerFeatures.SyncToolSpecification createSpecification() {
		// Get (or lazily create) the default JSON mapper from the MCP JSON module
		McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(TOOL_NAME)
			.title("Create TopLogic enumeration")
			.description(DESCRIPTION)
			.inputSchema(jsonMapper, INPUT_SCHEMA_JSON)
			.build();

		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(EnumerationCreationTool::handleToolRequest)
			.build();
	}

	/**
	 * Handles tool requests for creating enumerations.
	 *
	 * @param exchange
	 *        The MCP server exchange for interacting with the client.
	 * @param request
	 *        The tool call request (includes tool name, arguments, etc.).
	 * @return The result of the enumeration creation operation.
	 */
	private static McpSchema.CallToolResult handleToolRequest(
			McpSyncServerExchange exchange,
			CallToolRequest request) {

		Map<String, Object> arguments = request.arguments();

		return ThreadContextManager.inSystemInteraction(
			EnumerationCreationTool.class,
			() -> createEnumeration(arguments));
	}

	/**
	 * Creates or retrieves an enumeration based on the given arguments.
	 *
	 * @param arguments
	 *        The tool call arguments containing the module name, enumeration name, and optional
	 *        classifiers.
	 * @return The result indicating success or failure with enumeration details.
	 */
	private static McpSchema.CallToolResult createEnumeration(Map<String, Object> arguments) {
		// Extract and validate arguments
		final String moduleName;
		final String enumName;

		try {
			moduleName = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"moduleName",
				"Module name");

			enumName = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"enumName",
				"Enumeration name");
			validateEnumName(enumName);

		} catch (ToolArgumentUtil.ToolInputException e) {
			return createErrorResult(e.getMessage());
		}


		// Extract I18N for enumeration
		ToolI18NUtil.LocalizedTexts enumerationI18n = ToolI18NUtil.extractFromArguments(arguments);

		// Parse and validate classifiers array
		final List<ClassifierData> classifiersToCreate;
		try {
			classifiersToCreate = parseClassifiers(arguments);
		} catch (ToolArgumentUtil.ToolInputException e) {
			return createErrorResult(e.getMessage());
		} catch (IllegalArgumentException e) {
			return createErrorResult(e.getMessage());
		}

		// Look up module
		TLModel model = ModelService.getApplicationModel();
		TLModule module = model.getModule(moduleName);
		if (module == null) {
			return createErrorResult("Module '" + moduleName + "' does not exist");
		}

		// Check if enumeration already exists
		TLType existingType = module.getType(enumName);
		TLEnumeration enumeration;
		boolean created = false;

		if (existingType != null) {
			// Type already exists - ensure it's actually an enumeration
			if (!(existingType instanceof TLEnumeration)) {
				return createErrorResult("Type '" + enumName + "' exists in module '" + moduleName
					+ "' but is not an enumeration");
			}
			enumeration = (TLEnumeration) existingType;
			// Idempotent: return existing enumeration, ignore classifiers parameter
		} else {
			// Create new enumeration and classifiers in single transaction
			try (Transaction tx =
				PersistencyLayer.getKnowledgeBase().beginTransaction(ResKey.text("Create Enumeration"))) {

				enumeration = TLModelUtil.addEnumeration(module, enumName);
				created = true;

				// Create classifiers
				for (ClassifierData classifierData : classifiersToCreate) {
					TLClassifier classifier = TLModelUtil.addClassifier(enumeration, classifierData.name);
					if (classifierData.isDefault) {
						classifier.setDefault(true);
					}
				}

				tx.commit();

			} catch (Exception ex) {
				return createErrorResult("Failed to create enumeration: " + ex.getMessage());
			}

			// Apply I18N after successful transaction
			ToolI18NUtil.applyIfPresent(enumeration, enumerationI18n);

			// Apply classifier I18N
			for (ClassifierData classifierData : classifiersToCreate) {
				TLClassifier classifier = enumeration.getClassifier(classifierData.name);
				if (classifier != null) {
					ToolI18NUtil.applyIfPresent(classifier, classifierData.i18n);
				}
			}
		}

		try {
			String jsonResponse = buildSuccessJson(enumeration, created);
			return new McpSchema.CallToolResult(jsonResponse, false);
		} catch (Exception ex) {
			return createErrorResult("Failed to build success response: " + ex.getMessage());
		}
	}

	/**
	 * Validates that the enumeration name meets the naming requirements.
	 * <p>
	 * Enumeration names must be valid Java identifiers and may contain dots (for qualified names).
	 * </p>
	 *
	 * @param enumName
	 *        The enumeration name to validate.
	 * @throws ToolArgumentUtil.ToolInputException
	 *         If the enumeration name doesn't meet the requirements.
	 */
	private static void validateEnumName(String enumName)
			throws ToolArgumentUtil.ToolInputException {

		if (!ENUM_NAME_PATTERN.matcher(enumName).matches()) {
			throw new ToolArgumentUtil.ToolInputException(
				"Enumeration name must be a valid Java identifier and may contain dots " +
					"(e.g., 'MyEnum' or 'my.package.Status')");
		}
	}

	/**
	 * Validates that the classifier name meets the naming requirements.
	 * <p>
	 * Classifier names must start with a letter or underscore, may contain letters, numbers, dots,
	 * underscores, and minus signs, and must not end with a dot.
	 * </p>
	 *
	 * @param classifierName
	 *        The classifier name to validate.
	 * @throws ToolArgumentUtil.ToolInputException
	 *         If the classifier name doesn't meet the requirements.
	 */
	private static void validateClassifierName(String classifierName)
			throws ToolArgumentUtil.ToolInputException {

		if (!CLASSIFIER_NAME_PATTERN.matcher(classifierName).matches()) {
			throw new ToolArgumentUtil.ToolInputException(
				"Classifier name must start with a letter or underscore, may only contain letters, digits, " +
					"dots, underscores, and minus signs, and must not end with a dot.");
		}
	}

	/**
	 * Parses and validates the classifiers array from the arguments.
	 * <p>
	 * This method performs comprehensive validation:
	 * </p>
	 * <ul>
	 * <li>Validates each classifier name pattern</li>
	 * <li>Checks for duplicate classifier names</li>
	 * <li>Ensures only one classifier is marked as default</li>
	 * <li>Extracts I18N for each classifier</li>
	 * </ul>
	 *
	 * @param arguments
	 *        The tool call arguments containing the optional classifiers array.
	 * @return List of parsed classifier data, or empty list if no classifiers provided.
	 * @throws ToolArgumentUtil.ToolInputException
	 *         If validation fails.
	 * @throws IllegalArgumentException
	 *         If the classifiers array format is invalid.
	 */
	private static List<ClassifierData> parseClassifiers(Map<String, Object> arguments)
			throws ToolArgumentUtil.ToolInputException, IllegalArgumentException {

		List<Map<String, Object>> classifierMaps =
			ToolArgumentUtil.getOptionalObjectList(arguments, "classifiers");

		List<ClassifierData> result = new ArrayList<>();
		Set<String> classifierNames = new HashSet<>();
		String defaultClassifierName = null;

		for (Map<String, Object> classifierMap : classifierMaps) {
			// Extract and validate classifier name
			String classifierName;
			try {
				classifierName = ToolArgumentUtil.requireNonEmptyString(
					classifierMap,
					"classifierName",
					"Classifier name");
				validateClassifierName(classifierName);
			} catch (ToolArgumentUtil.ToolInputException e) {
				throw new ToolArgumentUtil.ToolInputException("Invalid classifier: " + e.getMessage());
			}

			// Check for duplicate names
			if (!classifierNames.add(classifierName)) {
				throw new ToolArgumentUtil.ToolInputException(
					"Duplicate classifier name: '" + classifierName + "'");
			}

			// Extract default flag
			boolean isDefault = ToolArgumentUtil.getBooleanArgument(classifierMap, "default", false);

			// Check for multiple defaults
			if (isDefault) {
				if (defaultClassifierName != null) {
					throw new ToolArgumentUtil.ToolInputException(
						"Only one classifier can be marked as default. Found multiple: '" +
							defaultClassifierName + "', '" + classifierName + "'");
				}
				defaultClassifierName = classifierName;
			}

			// Extract I18N for this classifier
			ToolI18NUtil.LocalizedTexts classifierI18n = ToolI18NUtil.extractFromArguments(classifierMap);

			result.add(new ClassifierData(classifierName, isDefault, classifierI18n));
		}

		return result;
	}

	/**
	 * Builds a JSON response for successful enumeration creation.
	 *
	 * @param enumeration
	 *        The created or retrieved enumeration.
	 * @param newlyCreated
	 *        Whether the enumeration was newly created or already existed.
	 * @return JSON string containing the success response.
	 * @throws IOException
	 *         If JSON writing fails.
	 */
	private static String buildSuccessJson(TLEnumeration enumeration, boolean newlyCreated) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = JsonResponseBuilder.createWriter(buffer)) {
			json.beginObject();

			json.name("success").value(true);
			json.name("enumeration").beginObject();
			json.name("name").value(enumeration.getName());
			json.name("tid").value(enumeration.tId().asString());
			json.name("module").value(enumeration.getModule().getName());
			json.name("classifierCount").value(enumeration.getClassifiers().size());

			// Write classifiers array
			json.name("classifiers").beginArray();
			for (TLClassifier classifier : enumeration.getClassifiers()) {
				json.beginObject();
				json.name("name").value(classifier.getName());
				json.name("default").value(classifier.isDefault());

				// Include label and description for classifiers (for newly created enumerations)
				if (newlyCreated) {
					JsonResponseBuilder.writeLabelAndDescription(json,
						TLModelNamingConvention.resourceKey(classifier));
				}

				json.endObject();
			}
			json.endArray();

			// Add label and description from enumeration resource key (optional)
			JsonResponseBuilder.writeLabelAndDescription(json,
				TLModelNamingConvention.getTypeLabelKey(enumeration));

			json.endObject();
			json.name("created").value(newlyCreated);

			if (newlyCreated) {
				int count = enumeration.getClassifiers().size();
				String classifierText = count == 1 ? "classifier" : "classifiers";
				json.name("message").value("Enumeration '" + enumeration.getName() +
					"' with " + count + " " + classifierText +
					" created successfully in module '" + enumeration.getModule().getName() + "'");
			} else {
				json.name("message").value("Enumeration '" + enumeration.getName() +
					"' already existed in module '" + enumeration.getModule().getName() + "'");
			}

			json.endObject();
		}
		return buffer.toString();
	}

	/**
	 * Creates an error response.
	 *
	 * @param errorMessage
	 *        The error message to return.
	 * @return CallToolResult containing the error.
	 */
	private static McpSchema.CallToolResult createErrorResult(String errorMessage) {
		try {
			StringWriter buffer = new StringWriter();
			try (JsonWriter json = JsonResponseBuilder.createWriter(buffer)) {
				json.beginObject();
				json.name("success").value(false);
				json.name("error").value(errorMessage);
				json.name("enumeration").nullValue();
				json.name("created").value(false);
				json.endObject();
			}
			return new McpSchema.CallToolResult(buffer.toString(), true);
		} catch (IOException ex) {
			// Fallback if JSON creation fails
			String fallbackJson = JsonResponseBuilder.buildErrorJson(errorMessage);
			return new McpSchema.CallToolResult(fallbackJson, true);
		}
	}
}
