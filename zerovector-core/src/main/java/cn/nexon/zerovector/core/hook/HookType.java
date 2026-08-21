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

package cn.nexon.zerovector.core.hook;

public enum HookType {
    DOCUMENT_UPLOAD_START,
    DOCUMENT_UPLOAD_END,
    DOCUMENT_UPLOAD_ERROR,
    
    DOCUMENT_COMPREHEND_START,
    DOCUMENT_COMPREHEND_END,
    DOCUMENT_COMPREHEND_ERROR,
    
    TREE_BUILD_START,
    TREE_BUILD_END,
    TREE_BUILD_ERROR,
    
    NAVIGATION_START,
    NAVIGATION_END,
    NAVIGATION_ERROR,
    NAVIGATION_STEP
}
