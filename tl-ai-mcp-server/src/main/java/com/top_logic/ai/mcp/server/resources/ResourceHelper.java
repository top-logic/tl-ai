/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.resources;

import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.util.ComputationEx2;

/**
 * Helper class for MCP resources that provides thread context management.
 *
 * <p>
 * All MCP resources that access the TopLogic database must execute within a proper thread context.
 * This helper provides a convenient way to wrap resource handlers with the necessary context setup.
 * </p>
 *
 * @author Bernhard Haumacher
 */
public class ResourceHelper {

	/**
	 * Executes the given computation within a system interaction context.
	 *
	 * <p>
	 * This ensures that TopLogic database access works correctly by establishing the required
	 * thread context before executing the resource handler logic.
	 * </p>
	 *
	 * @param <T>
	 *        The return type of the computation.
	 * @param <E1>
	 *        First potential exception type.
	 * @param <E2>
	 *        Second potential exception type.
	 * @param caller
	 *        The class requesting the context (used for identification).
	 * @param computation
	 *        The computation to execute with database access.
	 * @return The result of the computation.
	 * @throws E1
	 *         If the computation throws this exception.
	 * @throws E2
	 *         If the computation throws this exception.
	 */
	public static <T, E1 extends Throwable, E2 extends Throwable> T inSystemContext(
			Class<?> caller,
			ComputationEx2<T, E1, E2> computation) throws E1, E2 {
		return ThreadContextManager.inSystemInteraction(caller, computation);
	}
}
