/*
 * Copyright 2025 nexonlab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.nexon.zerovector.core.exception;

import java.util.List;

public class NavigationException extends RuntimeException {
    private final String query;
    private final String currentNodeId;
    private final String errorCode;
    private final ErrorLevel errorLevel;
    private final List<String> navigationPath;

    public NavigationException(String query, String currentNodeId, String errorCode, ErrorLevel errorLevel, String message, List<String> navigationPath, Throwable cause) {
        super(String.format("[%s][%s] Navigation failed for query '%s' at node '%s': %s", errorCode, errorLevel, query, currentNodeId, message), cause);
        this.query = query;
        this.currentNodeId = currentNodeId;
        this.errorCode = errorCode;
        this.errorLevel = errorLevel != null ? errorLevel : ErrorLevel.ERROR;
        this.navigationPath = navigationPath;
    }

    public NavigationException(String query, String currentNodeId, String errorCode, ErrorLevel errorLevel, String message, List<String> navigationPath) {
        this(query, currentNodeId, errorCode, errorLevel, message, navigationPath, null);
    }

    public NavigationException(String query, String currentNodeId, String errorCode, String message, Throwable cause) {
        this(query, currentNodeId, errorCode, ErrorLevel.ERROR, message, null, cause);
    }

    public NavigationException(String query, String currentNodeId, String errorCode, String message) {
        this(query, currentNodeId, errorCode, ErrorLevel.ERROR, message, null, null);
    }

    public String getQuery() {
        return query;
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public ErrorLevel getErrorLevel() {
        return errorLevel;
    }

    public List<String> getNavigationPath() {
        return navigationPath;
    }

    public static final String ERROR_CODE_TREE_NOT_INITIALIZED = "NAV_001";
    public static final String ERROR_CODE_NODE_NOT_FOUND = "NAV_002";
    public static final String ERROR_CODE_NO_NAVIGATOR = "NAV_003";
    public static final String ERROR_CODE_LLM_DECISION_FAILED = "NAV_004";
    public static final String ERROR_CODE_MAX_STEPS_EXCEEDED = "NAV_005";
    public static final String ERROR_CODE_NO_RESULTS = "NAV_006";
    public static final String ERROR_CODE_INVALID_QUERY = "NAV_007";
}
