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
├── src/test/java/                              # Test sources
└── src/test/webapp/WEB-INF/                    # Test configurations
```

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

## Build Artifacts

Build output includes:
- Standard JAR/WAR files in `target/`
- Packaged applications in `target/<module>-<version>-SNAPSHOT-app/`
- Docker build scripts in `src/main/docker/`
- Debian package configurations in `src/main/deb/`

## Notes

- Build artifacts are excluded from Git (in `.gitignore`)
- Temporary files go in `tmp/` directory (also excluded from Git)
- The project uses UTF-8 encoding throughout
- Test framework is JUnit (via TestCase base class)
