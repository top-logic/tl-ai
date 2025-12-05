/*
 * Copyright (c) 2025 TopLogic. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.tools;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

import com.top_logic.ai.mcp.server.util.JsonResponseBuilder;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.util.ResKey;
import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLModule;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.model.visit.LabelVisitor;
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
		final String moduleName;
		try {
			moduleName = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"moduleName",
				"Module name");
		} catch (ToolArgumentUtil.ToolInputException e) {
			return createErrorResult(e.getMessage());
		}

		TLModel model = ModelService.getApplicationModel();
		TLModule module = model.getModule(moduleName);
		boolean created = false;

		if (module == null) {
			// Extract I18N once, for reuse in other tools as well
			ToolI18NUtil.LocalizedTexts i18n = ToolI18NUtil.extractFromArguments(arguments);

			try (Transaction tx =
				PersistencyLayer.getKnowledgeBase().beginTransaction(ResKey.text("Create Module"))) {

				module = TLModelUtil.addModule(model, moduleName);
				created = true;

				tx.commit();

			} catch (Exception ex) {
				return createErrorResult("Failed to create module: " + ex.getMessage());
			}

			ToolI18NUtil.applyIfPresent(module, i18n);
		}

		try {
			String jsonResponse = buildSuccessJson(module, created);
			return new McpSchema.CallToolResult(jsonResponse, false);
		} catch (Exception ex) {
			return createErrorResult("Failed to build success response: " + ex.getMessage());
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
		try (JsonWriter json = JsonResponseBuilder.createWriter(buffer)) {
			json.beginObject();

			json.name("success").value(true);
			json.name("module").beginObject();
			json.name("name").value(module.getName());
			json.name("tid").value(module.tId().asString());
			json.name("typeCount").value(module.getTypes().size());

			// Add label and description from module resource key (optional)
			JsonResponseBuilder.writeLabelAndDescription(json, LabelVisitor.getModuleResourceKey(module));

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
	 * Creates an error response.
	 *
	 * @param errorMessage
	 *        The error message to return.
	 * @return CallToolResult containing the error.
	 */
	private static McpSchema.CallToolResult createErrorResult(String errorMessage) {
		String errorJson = JsonResponseBuilder.buildToolErrorJson(errorMessage);
		return new McpSchema.CallToolResult(errorJson, true);
	}
}
