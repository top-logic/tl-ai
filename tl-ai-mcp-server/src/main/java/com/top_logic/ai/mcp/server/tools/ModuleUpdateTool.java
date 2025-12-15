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
import com.top_logic.model.visit.LabelVisitor;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * Tool for updating internationalized labels and descriptions for existing TopLogic modules.
 *
 * This tool allows updating the display name and description of modules in both
 * English and German. The module name itself cannot be changed as it serves as
 * the unique identifier in the TopLogic model.
 */
public class ModuleUpdateTool {

	/** Tool name for the module update functionality. */
	public static final String TOOL_NAME = "update-module";

	/** Tool description. */
	private static final String DESCRIPTION =
		"Update internationalized labels and descriptions for an existing TopLogic module";

	/** JSON Schema for the tool input arguments. */
	private static final String INPUT_SCHEMA_JSON = """
		{
		  "type": "object",
		  "properties": {
		    "moduleName": {
		      "type": "string",
		      "description": "Name of the module to update"
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
		      "additionalProperties": false
		    }
		  },
		  "required": ["moduleName"],
		  "additionalProperties": false
		}
		""";

	/**
	 * Creates the MCP tool specification for the module update functionality.
	 *
	 * @return The tool specification that can be registered with the MCP server.
	 */
	public static McpServerFeatures.SyncToolSpecification createSpecification() {
		McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(TOOL_NAME)
			.title("Update TopLogic Module")
			.description(DESCRIPTION)
			.inputSchema(jsonMapper, INPUT_SCHEMA_JSON)
			.build();

		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(ModuleUpdateTool::handleToolRequest)
			.build();
	}

	/**
	 * Handles the tool request for updating a module.
	 *
	 * @param exchange The MCP server exchange context.
	 * @param request The tool request containing the arguments.
	 * @return The tool result with success/error information.
	 */
	private static McpSchema.CallToolResult handleToolRequest(
			McpSyncServerExchange exchange,
			CallToolRequest request) {

		Map<String, Object> arguments = request.arguments();

		return ThreadContextManager.inSystemInteraction(
			ModuleUpdateTool.class,
			() -> updateModule(arguments));
	}

	/**
	 * Updates the specified module with the provided internationalization data.
	 *
	 * @param arguments The tool arguments containing module name and I18N data.
	 * @return The tool result with updated module information.
	 */
	private static McpSchema.CallToolResult updateModule(Map<String, Object> arguments) {
		// Validate and extract module name
		final String moduleName;
		try {
			moduleName = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"moduleName",
				"Module name");
		} catch (ToolArgumentUtil.ToolInputException e) {
			return createErrorResult(e.getMessage());
		}

		// Retrieve module and validate existence
		TLModel model = ModelService.getApplicationModel();
		TLModule module = model.getModule(moduleName);

		if (module == null) {
			return createErrorResult("Module '" + moduleName + "' not found");
		}

		// Extract I18N data
		ToolI18NUtil.LocalizedTexts i18n = ToolI18NUtil.extractFromArguments(arguments);

		// Validate that at least one I18N field is provided
		if (!i18n.hasAny()) {
			return createErrorResult("At least one of 'label' or 'description' must be provided");
		}

		// Apply updates in transaction
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction(
				ResKey.text("Update Module"))) {

			ToolI18NUtil.applyIfPresent(module, i18n);

			tx.commit();

			String jsonResponse = buildSuccessJson(module);
			return new McpSchema.CallToolResult(jsonResponse, false);

		} catch (Exception ex) {
			return createErrorResult("Failed to update module: " + ex.getMessage());
		}
	}

	/**
	 * Builds a JSON response for successful module updates.
	 *
	 * @param module The updated module.
	 * @return JSON string with success information and updated module details.
	 */
	private static String buildSuccessJson(TLModule module) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = JsonResponseBuilder.createWriter(buffer)) {
			json.beginObject();

			json.name("success").value(true);
			json.name("updated").value(true);

			json.name("module").beginObject();
			json.name("name").value(module.getName());
			json.name("tid").value(module.tId().asString());
			json.name("typeCount").value(module.getTypes().size());

			// Add updated label and description
			JsonResponseBuilder.writeLabelAndDescription(json, LabelVisitor.getModuleResourceKey(module));

			json.endObject();

			json.name("message").value("Module '" + module.getName() + "' updated successfully");

			json.endObject();
		}
		return buffer.toString();
	}

	/**
	 * Creates a standardized error response.
	 *
	 * @param errorMessage The error message to include in the response.
	 * @return A CallToolResult containing the error information.
	 */
	private static McpSchema.CallToolResult createErrorResult(String errorMessage) {
		String errorJson = JsonResponseBuilder.buildToolErrorJson(errorMessage);
		return new McpSchema.CallToolResult(errorJson, false);
	}
}