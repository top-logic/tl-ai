/*
 * Copyright (c) 2025 TopLogic. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.tools;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Map;

import com.top_logic.base.config.i18n.Internationalized;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourceTransaction;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.element.layout.meta.TLMetaModelUtil;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLModule;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.model.visit.LabelVisitor;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * MCP tool for creating new TopLogic modules.
 * <p>
 * This tool provides functionality to create new modules in the TopLogic application model using
 * {@link TLModelUtil#makeModule(TLModel, String)}. If a module with the given name already exists,
 * the existing module is returned.
 * </p>
 */
public class ModuleCreationTool {

	/** Tool name for the module creation functionality. */
	public static final String TOOL_NAME = "create-module";

	/** Tool description. */
	private static final String DESCRIPTION = "Create a new TopLogic module or get an existing one by name";

	/** JSON Schema for the tool input. */
	private static final String INPUT_SCHEMA_JSON = """
			{
			  "type": "object",
			  "properties": {
			    "moduleName": {
			      "type": "string",
			      "description": "Name of the module to create"
			    },
			    "label": {
			      "type": "object",
			      "properties": {
			        "en": {
			          "type": "string",
			          "description": "English label for the module"
			        },
			        "de": {
			          "type": "string",
			          "description": "German label for the module"
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
			          "description": "English description for the module"
			        },
			        "de": {
			          "type": "string",
			          "description": "German description for the module"
			        }
			      },
			      "required": ["en", "de"],
			      "additionalProperties": false
			    }
			  },
			  "required": ["moduleName"],
			  "additionalProperties": false
			}
			""";

	/**
	 * Creates the MCP tool specification for module creation.
	 *
	 * @return The tool specification that can be registered with the MCP server.
	 */
	public static McpServerFeatures.SyncToolSpecification createSpecification() {
		// Get (or lazily create) the default JSON mapper from the MCP JSON module
		McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(TOOL_NAME)
			.title("Create TopLogic module")
			.description(DESCRIPTION)
			.inputSchema(jsonMapper, INPUT_SCHEMA_JSON)
			.build();

		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(ModuleCreationTool::handleToolRequest)
			.build();
	}

	/**
	 * Handles tool requests for creating modules.
	 *
	 * @param exchange
	 *        The MCP server exchange for interacting with the client.
	 * @param request
	 *        The tool call request (includes tool name, arguments, etc.).
	 * @return The result of the module creation operation.
	 */
	private static McpSchema.CallToolResult handleToolRequest(
			McpSyncServerExchange exchange,
			CallToolRequest request) {

		Map<String, Object> arguments = request.arguments();

		return ThreadContextManager.inSystemInteraction(
			ModuleCreationTool.class,
			() -> createModule(arguments));
	}

	/**
	 * Creates or retrieves a module based on the given arguments.
	 *
	 * @param arguments
	 *        The tool call arguments containing the module name.
	 * @return The result indicating success or failure with module details.
	 */
	private static McpSchema.CallToolResult createModule(Map<String, Object> arguments) {
		if (arguments == null) {
			return createErrorResult("Module name is required");
		}

		Object moduleNameObj = arguments.get("moduleName");
		if (moduleNameObj == null) {
			return createErrorResult("Module name is required");
		}

		String moduleName = moduleNameObj.toString().trim();
		if (moduleName.isEmpty()) {
			return createErrorResult("Module name cannot be empty");
		}

		// Get the application model first
		TLModel model = ModelService.getApplicationModel();

		// Check if module exists before deciding to use transaction
		boolean existedBefore = (model.getModule(moduleName) != null);

		if (existedBefore) {
			// Module already exists, return it without transaction
			try {
				TLModule module = model.getModule(moduleName);
				String jsonResponse = buildSuccessJson(module, false);
				return new McpSchema.CallToolResult(jsonResponse, false);
			} catch (Exception ex) {
				return createErrorResult("Failed to retrieve existing module: " + ex.getMessage());
			}
		} else {
			// Extract optional labels and descriptions
			String labelEng = extractLocalizedMessage(arguments, "label", "en");
			String labelDe = extractLocalizedMessage(arguments, "label", "de");
			String descEng = extractLocalizedMessage(arguments, "description", "en");
			String descDe = extractLocalizedMessage(arguments, "description", "de");

			TLModule module;
			// Module doesn't exist, create it with transaction
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction(ResKey.text("Create Module"))) {
				try {
					module = TLModelUtil.makeModule(model, moduleName);

					tx.commit();
				} catch (Exception ex) {
					tx.rollback();
					return createErrorResult("Failed to create module: " + ex.getMessage());
				}
			}

			// Only set I18N if labels or descriptions are provided
			if (labelEng != null || labelDe != null || descEng != null || descDe != null) {
				Internationalized i18N = TypedConfiguration.newConfigItem(Internationalized.class);

				// Build label ResKey with extracted values
				ResKey.Builder labelBuilder = ResKey.builder();
				if (labelEng != null) {
					labelBuilder.add(Locale.ENGLISH, labelEng);
				}
				if (labelDe != null) {
					labelBuilder.add(Locale.GERMAN, labelDe);
				}
				if (labelEng != null || labelDe != null) {
					i18N.setLabel(labelBuilder.build());
				}

				// Build description ResKey with extracted values
				ResKey.Builder descBuilder = ResKey.builder();
				if (descEng != null) {
					descBuilder.add(Locale.ENGLISH, descEng);
				}
				if (descDe != null) {
					descBuilder.add(Locale.GERMAN, descDe);
				}
				if (descEng != null || descDe != null) {
					i18N.setDescription(descBuilder.build());
				}

				try (ResourceTransaction tx = ResourcesModule.getInstance().startResourceTransaction()) {
					TLMetaModelUtil.saveI18NForPart(module, i18N, tx);
					tx.commit();
				}
			}

			try {
				String jsonResponse = buildSuccessJson(module, true);
				return new McpSchema.CallToolResult(jsonResponse, false);
			} catch (Exception ex) {
				return createErrorResult("Failed to build success response: " + ex.getMessage());
			}
		}
	}

	/**
	 * Builds a JSON response for successful module creation.
	 *
	 * @param module
	 *        The created or retrieved module.
	 * @param newlyCreated
	 *        Whether the module was newly created or already existed.
	 * @return JSON string containing the success response.
	 * @throws IOException
	 *         If JSON writing fails.
	 */
	private static String buildSuccessJson(TLModule module, boolean newlyCreated) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(buffer)) {
			json.setIndent("  ");
			json.beginObject();

			json.name("success").value(true);
			json.name("module").beginObject();
			json.name("name").value(module.getName());
			json.name("tid").value(module.tId().asString());
			json.name("typeCount").value(module.getTypes().size());

			// Get the resource key for the module (handles defaults if no annotation)
			ResKey moduleKey = LabelVisitor.getModuleResourceKey(module);
			Resources resources = Resources.getInstance();

			// Add label from the resource key (optional)
			String label = resources.getString(moduleKey, null);
			if (label != null && !label.isEmpty()) {
				json.name("label").value(label);
			}

			// Add description from tooltip sub-key (optional)
			ResKey tooltipKey = moduleKey.tooltip();
			String description = resources.getString(tooltipKey, null);
			if (description != null && !description.isEmpty()) {
				json.name("description").value(description);
			}

			json.endObject();
			json.name("created").value(newlyCreated);

			if (newlyCreated) {
				json.name("message").value("Module '" + module.getName() + "' created successfully");
			} else {
				json.name("message").value("Module '" + module.getName() + "' already existed");
			}

			json.endObject();
		}
		return buffer.toString();
	}

	/**
	 * Extracts a language-specific value from a nested object.
	 *
	 * @param arguments
	 *        The tool call arguments.
	 * @param objectKey
	 *        The key for the nested object (e.g., "label" or "description").
	 * @param languageKey
	 *        The language key within the object (e.g., "en" or "de").
	 * @return The extracted value or null if not provided.
	 */
	@SuppressWarnings("unchecked")
	private static String extractLocalizedMessage(Map<String, Object> arguments, String objectKey, String languageKey) {
		Object nestedObj = arguments.get(objectKey);
		if (nestedObj instanceof Map) {
			Map<String, Object> nestedMap = (Map<String, Object>) nestedObj;
			Object value = nestedMap.get(languageKey);
			if (value != null) {
				return value.toString().trim();
			}
		}
		return null;
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
			try (JsonWriter json = new JsonWriter(buffer)) {
				json.setIndent("  ");
				json.beginObject();
				json.name("success").value(false);
				json.name("error").value(errorMessage);
				json.name("module").nullValue();
				json.name("created").value(false);
				json.endObject();
			}

			return new McpSchema.CallToolResult(buffer.toString(), true);
		} catch (IOException ex) {
			// Fallback to simple error message if JSON creation fails
			String fallback = "{\"success\": false, \"error\": \"" + errorMessage.replace("\"", "\\\"") + "\"}";
			return new McpSchema.CallToolResult(fallback, true);
		}
	}
}
