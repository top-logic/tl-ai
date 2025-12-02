# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a TopLogic-based AI application with MCP (Model Context Protocol) server integration. The project is a multi-module Maven build consisting of:

- **tl-ai-mcp-server**: Core MCP server module that provides AI integration capabilities through the TopLogic framework
- **tl-ai-demo**: Demo application showcasing the MCP server functionality

Both modules extend the TopLogic platform (version 7.10.0-SNAPSHOT), a Java-based enterprise application framework with model-driven development capabilities.

## Build Commands

### Build the entire project
```bash
mvn clean install
```

### Build a specific module
```bash
mvn clean install -pl tl-ai-mcp-server
mvn clean install -pl tl-ai-demo
```

### Run tests
```bash
mvn test
```

### Run tests for a specific module
```bash
mvn test -pl tl-ai-mcp-server
mvn test -pl tl-ai-demo
```

### Skip tests during build
```bash
mvn clean install -DskipTests
```

### Package application
```bash
mvn package
```

## Project Structure

### Module Architecture

- **Parent POM** (`pom.xml`): Defines the multi-module structure
  - **tl-ai-mcp-server**: Core library module
    - Parent: `tl-parent-core` (TopLogic core parent)
    - Provides MCP server functionality
    - Dependencies: `tl-model-search`, `tl-contact`
  - **tl-ai-demo**: Application module
    - Parent: `tl-parent-app` (TopLogic application parent)
    - Depends on `tl-ai-mcp-server`
    - Includes full TopLogic application stack (themes, BPE, monitoring, etc.)

### Source Layout

Each module follows standard Maven structure:
```
<module>/
├── src/main/java/com/top_logic/ai/<module>/  # Java source code
├── src/main/webapp/WEB-INF/                    # Web application resources
│   ├── conf/                                   # Application configuration
│   ├── model/                                  # TopLogic model definitions
│   ├── autoconf/                               # Auto-configuration
│   ├── kbase/                                  # Knowledge base
│   ├── layouts/                                # UI layouts
│   └── data/                                   # Application data
├── src/main/resources/META-INF/                # Module metadata (web-fragment.xml)
├── src/test/java/                              # Test sources
└── src/test/webapp/WEB-INF/                    # Test configurations
```

**Important:** TopLogic modules must use `web-fragment.xml` for servlet registration, not `web.xml`. Only top-level applications should have a `web.xml` deployment descriptor.

### Key Configuration Files

- **Model Definition**: `src/main/webapp/WEB-INF/model/tl.ai.<module>.model.xml`
  - Defines application types using TopLogic's dynamic type system
  - Uses XML schema at `http://www.top-logic.com/ns/dynamic-types/6.0`
  - Generated code goes to `com.top_logic.ai.<module>.model` (interface) and `.impl` (implementation)

- **Application Configuration**: `src/main/webapp/WEB-INF/conf/tl-ai-<module>.conf.xml`
  - Main configuration file for the application

- **Auto-configuration**: `src/main/webapp/WEB-INF/autoconf/model.tl.ai.<module>.config.xml`
  - Automatic configuration for model integration

### TopLogic Framework Integration

This project builds on the TopLogic platform, which provides:
- Model-driven development with XML-based type definitions
- Dynamic type system for runtime type creation
- Web application framework with layouts and themes
- Knowledge base and migration support
- Internationalization (German and English resources)

The framework uses a parent POM hierarchy where modules inherit from either:
- `tl-parent-core`: For library/core modules
- `tl-parent-app`: For deployable application modules

## Development Workflow

1. **Adding Application Types**: Edit the model XML file in `src/main/webapp/WEB-INF/model/` to define new types
2. **Configuration Changes**: Update files in `src/main/webapp/WEB-INF/conf/`
3. **Custom Java Code**: Add to `src/main/java/com/top_logic/ai/<module>/`
4. **Tests**: Place in `src/test/java/com/top_logic/ai/<module>/`

## MCP Server Configuration

The MCP (Model Context Protocol) server is configured through two separate mechanisms:

### Servlet Mapping (web-fragment.xml)

The `tl-ai-mcp-server` module uses a `web-fragment.xml` file (not `web.xml`) to register its servlet. This follows TopLogic's modular architecture where:
- **Modules** use `web-fragment.xml` to register servlets and filters
- **Applications** use `web.xml` as the main deployment descriptor

The servlet mapping controls which URL patterns are routed to the MCP servlet:

```xml
<web-fragment xmlns="http://xmlns.jcp.org/xml/ns/javaee"
  xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-fragment_3_1.xsd"
  version="3.1">
    <name>tl-ai-mcp-server</name>

    <servlet>
        <servlet-name>MCPTransportServlet</servlet-name>
        <servlet-class>com.top_logic.ai.mcp.server.MCPTransportServlet</servlet-class>
        <async-supported>true</async-supported>
    </servlet>

    <servlet-mapping>
        <servlet-name>MCPTransportServlet</servlet-name>
        <url-pattern>/mcp/*</url-pattern>
    </servlet-mapping>
</web-fragment>
```

**Location:** `tl-ai-mcp-server/src/main/resources/META-INF/web-fragment.xml`

### Service Configuration (conf.config.xml)

Controls the MCP server behavior:

```xml
<config service-class="com.top_logic.ai.mcp.server.MCPServerService">
    <instance class="com.top_logic.ai.mcp.server.MCPServerService"
        server-name="TopLogic MCP Server"
        server-version="1.0.0"
        keep-alive-interval="30"
    />
</config>
```

**Important:** Endpoint paths (`/mcp/sse` and `/mcp/message`) are hardcoded in `MCPServerService` to match the servlet mapping in `web-fragment.xml`. Do not configure endpoints separately to avoid configuration redundancy and potential mismatches.

## Testing the MCP Server

### Python Test Client

A Python test client is available at the project root: `test_mcp.py`

**Requirements:**
- Python 3.x with virtual environment
- Install dependencies: `pip install requests`

**Usage:**
```bash
# Ensure the demo application is running on http://localhost:8080/tl-ai-demo/
python test_mcp.py
```

The test client demonstrates:
- Session initialization via JSON-RPC
- Server-Sent Events (SSE) for real-time communication
- Resource browsing and model introspection

## Build Artifacts

Build output includes:
- Standard JAR/WAR files in `target/`
- Packaged applications in `target/<module>-<version>-SNAPSHOT-app/`
- Docker build scripts in `src/main/docker/`
- Debian package configurations in `src/main/deb/`

## Code Style Guidelines

### Configuration Property Constants

When defining configuration property name constants in service configuration interfaces, always include a `@see` JavaDoc reference to the corresponding getter method.

**Pattern:**
```java
public interface Config<I extends MyService> extends ConfiguredManagedClass.Config<I> {

    /**
     * Configuration property name for my property.
     *
     * @see #getMyProperty()
     */
    String MY_PROPERTY = "my-property";

    /**
     * Description of what this property does.
     */
    @Name(MY_PROPERTY)
    @StringDefault("default value")
    String getMyProperty();
}
```

**Rationale:** This creates bidirectional documentation between the property name constant and its getter, making it easier to navigate and understand the configuration interface.

### Exception Handling in Services

When implementing TopLogic services (classes extending `ConfiguredManagedClass`), use appropriate exceptions for error handling:

- **ConfigurationError / ConfigurationException**: ONLY for reporting problems to end-users of the application (e.g., invalid user input, configuration errors that users can fix)
- **RuntimeException**: For technical errors in service initialization and operation (e.g., startup failures, resource initialization errors)

**Pattern for Service Startup:**
```java
@Override
protected void startUp() {
    super.startUp();

    try {
        // Service initialization code
        someResource.init();

    } catch (RuntimeException ex) {
        // Re-throw RuntimeExceptions as-is
        throw ex;
    } catch (Exception ex) {
        // Wrap checked exceptions in RuntimeException
        throw new RuntimeException("Failed to start service: " + ex.getMessage(), ex);
    }
}
```

**Rationale:** Service errors are technical issues not visible to end-users. `ConfigurationError` and `ConfigurationException` are reserved for user-facing validation and configuration problems. Using `RuntimeException` for service errors ensures proper separation of concerns between technical failures and user-actionable errors.

### Accessing Servlet Context Information

When implementing services that need access to the web application context (e.g., context path, servlet context), use the `ServletContextService`:

**Pattern:**
```java
import com.top_logic.basic.module.ServiceDependencies;
import com.top_logic.basic.module.services.ServletContextService;

@ServiceDependencies({
    ServletContextService.Module.class
})
public class MyService extends ConfiguredManagedClass<MyService.Config<?>> {

    @Override
    protected void startUp() {
        super.startUp();

        // Get the application context path
        String contextPath = ServletContextService.getInstance().getServletContext().getContextPath();

        // Use context path in service initialization
        String fullUrl = contextPath + "/my/endpoint";
    }
}
```

**Important:** When one service uses another service, you **must** declare the dependency using the `@ServiceDependencies` annotation. This ensures proper startup order - the `ServletContextService` will be started before your service.

**Rationale:** The `ServletContextService` provides access to servlet context information even outside of HTTP request handling. This is essential for services that need to construct URLs or access deployment-specific information during initialization. Declaring the dependency ensures that the servlet context is available when your service starts up.

## Git Commit Messages

When creating commit messages, follow these conventions:

**Structure:**
```
Short summary line (imperative mood, max 50 chars)

Detailed explanation of what changed and why (wrap at 72 chars).
Can span multiple paragraphs if needed.

- Bullet points for key changes
- Another change

User prompts:
> Original user request that led to this change
> Additional user clarification or feedback
```

**Important Rules:**
- **DO NOT** add Claude signature lines like "🤖 Generated with Claude Code" or "Co-Authored-By: Claude"
- **DO** include the user prompts that produced the commit as the last section
- Use imperative mood in the summary ("Add feature" not "Added feature")
- Separate summary from body with a blank line
- Wrap body text at 72 characters
- Use bullet points for listing multiple changes

**Example:**
```
Add module description to ModelModulesResource

Enhance the model modules resource to include module descriptions by
retrieving them from TLI18NKey annotations. This provides more context
for AI agents browsing the TopLogic model structure.

- Retrieve TLI18NKey annotation from each module
- Resolve the resource key to get the localized description
- Include description field in JSON output when available

User prompts:
> A TLModule actually has a description. Unfortunately this is not
> available as property or getter. In the UI it is possible to enter
> a description when creating a module. Use this description as
> additional information in the modules resource.
```

## Notes

- Build artifacts are excluded from Git (in `.gitignore`)
- Temporary files go in `tmp/` directory (also excluded from Git)
- The project uses UTF-8 encoding throughout
- Test framework is JUnit (via TestCase base class)