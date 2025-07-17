# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Sesame-TK is an Android Xposed module for automating Alipay Ant Forest energy collection and related tasks. It's an open-source fork of the original Sesame project with modular architecture and enhanced features.

## Build Commands

Since this is an Android project using Gradle, use these commands:

- **Build project**: `./gradlew build`
- **Build debug APK**: `./gradlew assembleDebug`
- **Build release APK**: `./gradlew assembleRelease`
- **Clean project**: `./gradlew clean`
- **Check code**: `./gradlew check`

The project has two build flavors:
- `normal`: Standard version with Java 17 compatibility
- `compatible`: Compatible version with Java 11 compatibility

## Code Architecture

### Core Components

1. **Model System** (`model/`): Modular configuration system
   - `BaseModel.java`: Core configuration module with timing, intervals, and basic settings
   - `Model.java`: Abstract base class for all feature modules
   - `ModelFields.java`: Field container for module configurations
   - `modelFieldExt/`: Type-specific field implementations (Boolean, Integer, String, etc.)

2. **Task System** (`task/`): Feature implementation modules
   - `BaseTask.java`: Abstract task executor with child task management
   - `antForest/`: Ant Forest energy collection tasks
   - `antFarm/`: Ant Farm automation tasks
   - `antOcean/`: Ant Ocean related tasks
   - Each task module follows the pattern: `{Feature}.java` + `{Feature}RpcCall.java`

3. **Hook System** (`hook/`): Xposed integration layer
   - `ApplicationHook.java`: Main Xposed entry point
   - `rpc/bridge/`: RPC communication bridge for different Alipay versions
   - `RequestManager.java`: HTTP request management

4. **UI System** (`ui/`, `newui/`): Configuration interfaces
   - `MainActivity.kt`: Legacy UI entry point
   - `MainActivityMaterial3.kt`: New Material 3 UI implementation
   - Web-based configuration interface in `assets/web/`

5. **Utility System** (`util/`): Support classes
   - `Maps/`: ID mapping managers for different features
   - `Log.java`: Centralized logging system
   - `RandomUtil.java`: Random number generation for human-like behavior

### Architecture Patterns

- **Modular Design**: Each feature is a self-contained module with its own Model and Task classes
- **Configuration-Driven**: All features use the Model system for settings management
- **Task Hierarchy**: BaseTask supports parent-child task relationships
- **RPC Abstraction**: Bridge pattern handles different Alipay version compatibility
- **Thread Management**: GlobalThreadPools manages concurrent task execution

## Development Environment

The project uses:
- **Language**: Java (primary) + Kotlin
- **Build System**: Gradle with Kotlin DSL
- **Android**: Target SDK 36, Min SDK 21
- **Dependencies**: 
  - Xposed API for hooking
  - Jackson for JSON processing (with compatibility variants)
  - Material Design 3 for UI
  - NanoHTTPD for web server functionality

## Debug Server

The `serve-debug/` directory contains a Python FastAPI server for debugging:
- **Setup**: Create virtual environment and install requirements
- **Run**: `python main.py`
- **Purpose**: Receives hook data forwarded from the Android module

## Key Development Notes

- **Version Compatibility**: Project maintains compatibility with multiple Alipay versions through RPC bridges
- **Modular Features**: Adding new features requires creating both Model and Task classes
- **Configuration System**: All settings are automatically handled through the Model field system
- **Timing Control**: Uses sophisticated timing mechanisms to avoid detection
- **Error Handling**: Comprehensive exception handling with configurable retry logic

## Configuration Management

The project uses a sophisticated configuration system where:
- Each feature extends `Model` class
- Configuration fields are defined using typed `ModelField` implementations
- Settings are automatically serialized/deserialized to JSON
- UI is auto-generated from field definitions

## Testing

Test execution is disabled in the build configuration (`testOptions.unitTests.all { it.enabled = false }`). Manual testing is done through the Xposed framework on Android devices.