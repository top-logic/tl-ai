/*
 * Copyright (c) 2025 TopLogic. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.tools;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.top_logic.ai.mcp.server.util.JsonResponseBuilder;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.util.ResKey;
import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
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
 * MCP tool for creating new TopLogic classes in existing modules.
 * <p>
 * This tool provides functionality to create new classes in TopLogic application model modules
 * using {@link TLModelUtil#addClass(TLModule, String)}. If a class with the given name already
 * exists in the module, the existing class is returned.
 * </p>
 */
public class ClassCreationTool {

	/** Tool name for the class creation functionality. */
	public static final String TOOL_NAME = "create-class";

	/** Tool description. */
	private static final String DESCRIPTION = "Create a new TopLogic class in an existing module or get an existing one by name";

	/** JSON Schema for the tool input. */
	private static final String INPUT_SCHEMA_JSON = """
			{
			  "type": "object",
			  "properties": {
			    "moduleName": {
			      "type": "string",
			      "description": "Name of the module where the class will be created"
			    },
			    "className": {
			      "type": "string",
			      "description": "Name of the class to create. Must start with an uppercase letter and contain only letters, numbers, and underscores."
			    },
			    "abstract": {
			      "type": "boolean",
			      "description": "Whether the class is abstract / cannot be instantiated directly (default: false)"
			    },
			    "final": {
			      "type": "boolean",
			      "description": "Whether the class is final / cannot be specialized (default: false)"
			    },
			    "generalizations": {
			      "type": "array",
			      "description": "Names of the classes this class directly extends.",
			      "items": {
			        "type": "string"
			      }
			    },
			    "label": {
			      "type": "object",
			      "properties": {
			        "en": {
			          "type": "string",
			          "description": "English label for the class"
			        },
			        "de": {
			          "type": "string",
			          "description": "German label for the class"
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
			          "description": "English description for the class"
			        },
			        "de": {
			          "type": "string",
			          "description": "German description for the class"
			        }
			      },
			      "required": ["en", "de"],
			      "additionalProperties": false
			    }
			  },
			  "required": ["moduleName", "className"],
			  "additionalProperties": false
			}
			""";

	/**
	 * Creates the MCP tool specification for class creation.
	 *
	 * @return The tool specification that can be registered with the MCP server.
	 */
	public static McpServerFeatures.SyncToolSpecification createSpecification() {
		// Get (or lazily create) the default JSON mapper from the MCP JSON module
		McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(TOOL_NAME)
			.title("Create TopLogic class")
			.description(DESCRIPTION)
			.inputSchema(jsonMapper, INPUT_SCHEMA_JSON)
			.build();

		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(ClassCreationTool::handleToolRequest)
			.build();
	}

	/**
	 * Handles tool requests for creating classes.
	 *
	 * @param exchange
	 *        The MCP server exchange for interacting with the client.
	 * @param request
	 *        The tool call request (includes tool name, arguments, etc.).
	 * @return The result of the class creation operation.
	 */
	private static McpSchema.CallToolResult handleToolRequest(
			McpSyncServerExchange exchange,
			CallToolRequest request) {

		Map<String, Object> arguments = request.arguments();

		return ThreadContextManager.inSystemInteraction(
			ClassCreationTool.class,
			() -> createClass(arguments));
	}

	/**
	 * Creates or retrieves a class based on the given arguments.
	 *
	 * @param arguments
	 *        The tool call arguments containing the module name and class name.
	 * @return The result indicating success or failure with class details.
	 */
	private static McpSchema.CallToolResult createClass(Map<String, Object> arguments) {
		// Extract and validate module name
		final String moduleName;
		try {
			moduleName = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"moduleName",
				"Module name");
		} catch (ToolArgumentUtil.ToolInputException e) {
			return createErrorResult(e.getMessage());
		}

		// Extract and validate class name
		final String className;
		try {
			className = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"className",
				"Class name");
			validateClassName(className);
		} catch (ToolArgumentUtil.ToolInputException e) {
			return createErrorResult(e.getMessage());
		}
		
		// Klassennamen aus Argumenten lesen
		List<String> generalizationNames;
		try {
			generalizationNames = ToolArgumentUtil.getOptionalStringList(arguments, "generalizations");
		} catch (IllegalArgumentException e) {
			return createErrorResult(e.getMessage());
		}

		// Extract optional cardinality settings
		boolean isAbstract = ToolArgumentUtil.getBooleanArgument(arguments, "abstract", false);
		boolean isFinal = ToolArgumentUtil.getBooleanArgument(arguments, "final", false);


		// Extract I18N once, for potential use if creating new class
		ToolI18NUtil.LocalizedTexts i18n = ToolI18NUtil.extractFromArguments(arguments);

		// Look up module
		TLModel model = ModelService.getApplicationModel();
		TLModule module = model.getModule(moduleName);
		if (module == null) {
			return createErrorResult("Module '" + moduleName + "' does not exist");
		}

		// TLClass-Generalizations-Objekte auflösen und Existenzen prüfen
		final List<TLClass> generalizations;
		try {
			generalizations = resolveGeneralizations(generalizationNames, module);
		} catch (ToolArgumentUtil.ToolInputException e) {
			return createErrorResult(e.getMessage());
		}

		// Check if class already exists
		TLType existingType = module.getType(className);
		TLClass tlClass;
		boolean created = false;

		if (existingType != null) {
			// Class already exists - ensure it's actually a class
			if (!(existingType instanceof TLClass)) {
				return createErrorResult("Type '" + className + "' exists in module '" + moduleName
					+ "' but is not a class");
			}
			tlClass = (TLClass) existingType;
		} else {
			// Create new class
			try (Transaction tx =
				PersistencyLayer.getKnowledgeBase().beginTransaction(ResKey.text("Create Class"))) {

				tlClass = TLModelUtil.addClass(module, className);
				tlClass.setAbstract(isAbstract);
				tlClass.setFinal(isFinal);
				tlClass.getGeneralizations().addAll(generalizations);
				created = true;

				tx.commit();

			} catch (Exception ex) {
				return createErrorResult("Failed to create class: " + ex.getMessage());
			}

			// Apply i18n only for newly created classes
			ToolI18NUtil.applyIfPresent(tlClass, i18n);
		}

		try {
			String jsonResponse = buildSuccessJson(tlClass, created);
			return new McpSchema.CallToolResult(jsonResponse, false);
		} catch (Exception ex) {
			return createErrorResult("Failed to build success response: " + ex.getMessage());
		}
	}

	/**
	 * Validates that the class name meets the naming requirements.
	 * <p>
	 * Class names must start with an uppercase letter and contain only letters, numbers, and
	 * underscores.
	 * </p>
	 *
	 * @param className
	 *        The class name to validate.
	 * @throws ToolArgumentUtil.ToolInputException
	 *         If the class name doesn't meet the requirements.
	 */
	private static void validateClassName(String className) throws ToolArgumentUtil.ToolInputException {
		if (!className.matches("^[A-Z][a-zA-Z0-9_]*$")) {
			throw new ToolArgumentUtil.ToolInputException(
				"Class name must start with an uppercase letter and contain only letters, numbers, and underscores");
		}
	}

	private static List<TLClass> resolveGeneralizations(
			List<String> generalizationNames,
			TLModule module) throws ToolArgumentUtil.ToolInputException {

		if (generalizationNames == null) {
			return Collections.emptyList();
		}

		List<TLClass> result = new ArrayList<>();
		for (String generalizationName : generalizationNames) {
			TLType type = module.getType(generalizationName);
			if (type == null) {
				throw new ToolArgumentUtil.ToolInputException(
					"Generalization class '" + generalizationName + "' does not exist in module '"
						+ module.getName() + "'.");
			}
			if (!(type instanceof TLClass)) {
				throw new ToolArgumentUtil.ToolInputException(
					"Type '" + generalizationName + "' exists in module '" + module.getName()
						+ "' but is not a class.");
			}
			result.add((TLClass) type);
		}
		return result;
	}

	/**
	 * Builds a JSON response for successful class creation.
	 *
	 * @param tlClass
	 *        The created or retrieved class.
	 * @param newlyCreated
	 *        Whether the class was newly created or already existed.
	 * @return JSON string containing the success response.
	 * @throws IOException
	 *         If JSON writing fails.
	 */
	private static String buildSuccessJson(TLClass tlClass, boolean newlyCreated) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = JsonResponseBuilder.createWriter(buffer)) {
			json.beginObject();

			json.name("success").value(true);
			json.name("class").beginObject();
			json.name("name").value(tlClass.getName());
			json.name("tid").value(tlClass.tId().asString());
			json.name("module").value(tlClass.getModule().getName());
			json.name("partCount").value(tlClass.getLocalClassParts().size());

			// Add label and description from type resource key (optional)
			JsonResponseBuilder.writeLabelAndDescription(json, TLModelNamingConvention.getTypeLabelKey(tlClass));

			json.endObject();
			json.name("created").value(newlyCreated);

			if (newlyCreated) {
				json.name("message").value("Class '" + tlClass.getName() + "' created successfully in module '"
					+ tlClass.getModule().getName() + "'");
			} else {
				json.name("message").value("Class '" + tlClass.getName() + "' already existed in module '"
					+ tlClass.getModule().getName() + "'");
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
				json.name("class").nullValue();
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
